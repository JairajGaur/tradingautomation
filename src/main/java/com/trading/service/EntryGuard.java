package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal entry guard for long/call entries, shared by all strategies.
 *
 * <p>A BUY is only permitted when BOTH conditions hold on the latest
 * <em>completed</em> bar:</p>
 * <ol>
 *   <li><b>Supertrend UP</b> on every configured timeframe (default 1m, 5m, 30m),
 *       ST(length, factor) = (7, 3).</li>
 *   <li><b>EMA stack</b> on 1-minute bars: EMA(fast) and EMA(mid) both above
 *       EMA(slow) — default 100 &amp; 200 above 600.</li>
 * </ol>
 *
 * <h2>Caching</h2>
 * <ul>
 *   <li><b>Per-cycle:</b> {@link #newCycle()} clears a short-lived cache so all
 *       checks within one evaluation reuse the same fetched bars (e.g. the M1
 *       bars serve both the M1 Supertrend and the EMA-stack check).</li>
 *   <li><b>M30 boundary:</b> the 30-minute Supertrend is recomputed only when the
 *       clock crosses a new :00/:30 boundary; between boundaries the last computed
 *       M30 direction is reused.</li>
 * </ul>
 */
@Service
public class EntryGuard {

    private static final Logger log = LoggerFactory.getLogger(EntryGuard.class);

    private final WebullProperties props;
    private final MarketDataService marketDataService;
    private final SupertrendService supertrend;

    public EntryGuard(WebullProperties props, MarketDataService marketDataService,
                      SupertrendService supertrend) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.supertrend = supertrend;
    }

    /** Outcome of an entry-guard evaluation. */
    public record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, "OK"); }
        static Decision block(String reason) { return new Decision(false, reason); }
    }

    // Per-cycle bar cache: key = ticker|timespan -> bars (oldest-first). Shared across
    // parallel worker threads within a single evaluation cycle (concurrent map).
    private volatile Map<String, List<Candle>> cycleBars = new ConcurrentHashMap<>();

    // M30 Supertrend cache per ticker (survives across cycles until next boundary).
    private record M30Cache(long boundaryEpochMin, Direction direction) {}
    private final Map<String, M30Cache> m30Cache = new ConcurrentHashMap<>();

    // Tickers currently "armed": the guard passed on the latest refresh and has not
    // dropped off. A BUY is only permitted while its ticker is armed. Latched with
    // no expiry — cleared only when the guard drops off (unarm) or on a buy.
    private final java.util.Set<String> armed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Starts a fresh per-cycle bar cache. Call once at the start of an evaluation
     * cycle (before any parallel per-ticker work). Swapping the map atomically gives
     * all workers in this cycle a clean, shared cache.
     */
    public void newCycle() {
        cycleBars = new ConcurrentHashMap<>();
    }

    /**
     * Re-evaluates the guard for {@code ticker} and updates its armed state:
     * <ul>
     *   <li>guard passes → ticker becomes/stays <b>armed</b>;</li>
     *   <li>guard fails  → ticker is <b>un-armed</b>.</li>
     * </ul>
     * The orchestrator calls this every minute for every watchlist ticker, so armed
     * state always reflects the latest guard evaluation — independent of any signal.
     *
     * @return the armed state after refresh (true = armed)
     */
    public boolean refreshArmed(String ticker) {
        Decision d = evaluate(ticker);
        if (d.allowed()) {
            if (armed.add(ticker)) {
                log.info("[EntryGuard] ARMED ticker={} (guard passed)", ticker);
            }
            return true;
        }
        if (armed.remove(ticker)) {
            log.info("[EntryGuard] UN-ARMED ticker={} (guard failed: {})", ticker, d.reason());
        }
        return false;
    }

    /** True when {@code ticker} is currently armed (guard last passed and hasn't dropped). */
    public boolean isArmed(String ticker) {
        // When the guard is disabled, everything is considered armed.
        return !props.entryGuard().enabled() || armed.contains(ticker);
    }

    /** Clears the armed state for a ticker (e.g. right after a buy fires). */
    public void clearArmed(String ticker) {
        if (armed.remove(ticker)) {
            log.debug("[EntryGuard] Cleared armed state for {}", ticker);
        }
    }

    /** Snapshot of the currently-armed tickers. */
    public java.util.Set<String> armedTickers() {
        return java.util.Set.copyOf(armed);
    }

    /** Clears ALL armed state and the M30 cache. Used by the daily reset (04:00 ET). */
    public void clearAll() {
        int n = armed.size();
        armed.clear();
        m30Cache.clear();
        log.info("[EntryGuard] Daily reset — cleared armed state for {} ticker(s) and M30 cache", n);
    }

    /**
     * Re-validates only the currently-armed tickers and un-arms any whose guard has
     * dropped. Called every minute so an armed ticker is dropped from the pending
     * list quickly when the guard breaks (continuous re-check for armed tickers).
     */
    public void revalidateArmed() {
        for (String ticker : armedTickers()) {
            revalidateArmedTicker(ticker);
        }
    }

    /**
     * Re-validates a single armed ticker and un-arms it if its guard has dropped.
     * Safe to call from parallel workers (armed set is concurrent). No-op if the
     * ticker is not currently armed.
     */
    public void revalidateArmedTicker(String ticker) {
        if (!armed.contains(ticker)) return;
        if (!evaluate(ticker).allowed()) {
            if (armed.remove(ticker)) {
                log.info("[EntryGuard] UN-ARMED ticker={} (guard dropped after arming)", ticker);
            }
        }
    }

    /**
     * Evaluates the guard for a prospective BUY on {@code ticker}.
     *
     * @return {@link Decision#allowed()} true to permit the buy, false with a reason otherwise
     */
    public Decision evaluate(String ticker) {
        if (!props.entryGuard().enabled()) {
            return Decision.allow();
        }

        // Evaluation order (fail-fast, cheapest/slowest-moving first):
        //   1) 30m Supertrend  2) EMA stack (1m)

        // ── 1. 30-minute Supertrend UP (uses the M30 boundary cache) ─────────
        Decision st30 = supertrendCheck(ticker, "M30");
        if (!st30.allowed()) return st30;

        // ── 2. EMA stack on 1m: fast & mid above slow ────────────────────────
        Decision ema = emaStackCheck(ticker);
        if (!ema.allowed()) return ema;

        log.info("[EntryGuard] PASS ticker={} — 30m ST up, EMA stack ok", ticker);
        return Decision.allow();
    }

    // -----------------------------------------------------------------------
    // Supertrend per timeframe (with M30 boundary caching)
    // -----------------------------------------------------------------------

    /** Latest-completed-bar Supertrend check for one timeframe → allow/block Decision. */
    private Decision supertrendCheck(String ticker, String timespan) {
        Direction dir = supertrendDirection(ticker, timespan);
        if (dir == null) {
            return Decision.block("ST_UNAVAILABLE_" + timespan);
        }
        if (dir != Direction.UP) {
            return Decision.block("ST_NOT_UP_" + timespan + " (dir=" + dir + ")");
        }
        return Decision.allow();
    }

    private Direction supertrendDirection(String ticker, String timespan) {
        // M30: reuse the cached direction until the next :00/:30 boundary.
        if ("M30".equalsIgnoreCase(timespan)) {
            long boundary = current30MinBoundaryEpochMin();
            M30Cache cached = m30Cache.get(ticker);
            if (cached != null && cached.boundaryEpochMin() == boundary) {
                log.debug("[EntryGuard] Reusing cached M30 ST for {} (boundary={})", ticker, boundary);
                return cached.direction();
            }
            Direction dir = computeLatestSupertrend(ticker, timespan);
            if (dir != null) {
                m30Cache.put(ticker, new M30Cache(boundary, dir));
            }
            return dir;
        }
        return computeLatestSupertrend(ticker, timespan);
    }

    /** Epoch-minute of the current 30-min boundary (floors now to :00 or :30). */
    private static long current30MinBoundaryEpochMin() {
        long nowMin = Instant.now().getEpochSecond() / 60;
        return (nowMin / 30) * 30;
    }

    /** Computes the latest COMPLETED-bar Supertrend direction, or null if unavailable. */
    private Direction computeLatestSupertrend(String ticker, String timespan) {
        int len = supertrend.length();
        // Need enough bars for a stable ATR seed; fetch a healthy window.
        int count = Math.max(len * 10, 120);
        List<Candle> bars = bars(ticker, timespan, count);
        // latestCompletedDirection drops the last (possibly in-progress) bar itself.
        Direction dir = supertrend.latestCompletedDirection(bars);
        if (dir == null) {
            log.warn("[EntryGuard] ST unavailable for {} {} (bars={})", ticker, timespan, bars.size());
        }
        return dir;
    }

    // -----------------------------------------------------------------------
    // EMA stack check
    // -----------------------------------------------------------------------

    private Decision emaStackCheck(String ticker) {
        int fast = props.entryGuard().emaFast();
        int mid  = props.entryGuard().emaMid();
        int slow = props.entryGuard().emaSlow();
        String tf = props.entryGuard().emaTimespan();

        // Slowest EMA needs the most history; fetch slow + a buffer.
        int count = slow + 100;
        List<Candle> bars = bars(ticker, tf, count);
        if (bars.size() < slow + 1) {
            return Decision.block("EMA_INSUFFICIENT_DATA (" + tf + " bars=" + bars.size() + ", need>" + slow + ")");
        }
        // Use closes of COMPLETED bars only (drop the last, possibly in-progress).
        List<BigDecimal> closes = new ArrayList<>(bars.size() - 1);
        for (int i = 0; i < bars.size() - 1; i++) {
            closes.add(bars.get(i).close());
        }
        if (closes.size() < slow) {
            return Decision.block("EMA_INSUFFICIENT_DATA (completed=" + closes.size() + ", need>=" + slow + ")");
        }

        BigDecimal emaFast = EmaCalculator.calculate(closes, fast);
        BigDecimal emaMid  = EmaCalculator.calculate(closes, mid);
        BigDecimal emaSlow = EmaCalculator.calculate(closes, slow);

        boolean fastAbove = emaFast.compareTo(emaSlow) > 0;
        boolean midAbove  = emaMid.compareTo(emaSlow) > 0;
        if (fastAbove && midAbove) {
            return Decision.allow();
        }
        return Decision.block("EMA_STACK_FAIL (ema" + fast + "=" + emaFast + " ema" + mid + "=" + emaMid
                + " ema" + slow + "=" + emaSlow + " — need both above ema" + slow + ")");
    }

    // -----------------------------------------------------------------------
    // Per-cycle bar fetch/cache
    // -----------------------------------------------------------------------

    private List<Candle> bars(String ticker, String timespan, int count) {
        String key = ticker + "|" + timespan;
        Map<String, List<Candle>> cache = cycleBars;
        List<Candle> cached = cache.get(key);
        // Reuse if we already fetched at least as many bars this cycle.
        if (cached != null && cached.size() >= count) {
            return cached;
        }
        try {
            List<Candle> bars = marketDataService.fetchHistoricalBars(ticker, count, timespan, null);
            cache.put(key, bars);
            return bars;
        } catch (Exception e) {
            log.warn("[EntryGuard] Failed to fetch {} {} bars for {}: {}", count, timespan, ticker, e.getMessage());
            return cached != null ? cached : List.of();
        }
    }
}
