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
import java.util.ArrayList;
import java.util.List;

/**
 * Universal entry guard for long/call entries, shared by all strategies.
 *
 * <p>A BUY is only permitted when BOTH conditions hold on the latest
 * <em>completed</em> bar:</p>
 * <ol>
 *   <li><b>30m Supertrend UP</b>, ST params from {@code webull.supertrend}.</li>
 *   <li><b>EMA stack</b> on 1-minute bars: EMA(fast) and EMA(mid) both above
 *       EMA(slow) — default 100 &amp; 200 above 600.</li>
 * </ol>
 *
 * <p>All bar data is read from the shared {@link BarDataManager} (single fetcher).</p>
 */
@Service
public class EntryGuard {

    private static final Logger log = LoggerFactory.getLogger(EntryGuard.class);

    private final WebullProperties props;
    private final BarDataManager barData;
    private final SupertrendService supertrend;

    public EntryGuard(WebullProperties props, BarDataManager barData,
                      SupertrendService supertrend) {
        this.props = props;
        this.barData = barData;
        this.supertrend = supertrend;
    }

    /** Outcome of an entry-guard evaluation. */
    public record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, "OK"); }
        static Decision block(String reason) { return new Decision(false, reason); }
    }

    // Tickers currently "armed": the guard passed on the latest refresh and has not
    // dropped off. A BUY is only permitted while its ticker is armed. Latched with
    // no expiry — cleared only when the guard drops off (unarm) or on a buy.
    private final java.util.Set<String> armed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Last guard evaluation reason per ticker (e.g. "OK", "ST_NOT_UP_M30",
    // "EMA_STACK_FAIL...", "ST_UNAVAILABLE_M30"). Lets callers log WHY a buy was gated.
    private final java.util.Map<String, String> lastReason = new java.util.concurrent.ConcurrentHashMap<>();

    /** The most recent guard reason for {@code ticker}, or a default if never evaluated. */
    public String lastReason(String ticker) {
        return lastReason.getOrDefault(ticker, "NOT_EVALUATED");
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
        lastReason.put(ticker, d.reason());
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

    /** Clears ALL armed state. Used by the daily reset (04:00 ET). */
    public void clearAll() {
        int n = armed.size();
        armed.clear();
        log.info("[EntryGuard] Daily reset — cleared armed state for {} ticker(s)", n);
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
        Decision d = evaluate(ticker);
        lastReason.put(ticker, d.reason());
        if (!d.allowed()) {
            if (armed.remove(ticker)) {
                log.info("[EntryGuard] UN-ARMED ticker={} (guard dropped after arming: {})", ticker, d.reason());
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

        // ── 1. 30-minute Supertrend UP (bars cached by BarDataManager) ───────
        Decision st30 = supertrendCheck(ticker, "M30");
        if (!st30.allowed()) return st30;

        // ── 2. EMA stack on 1m: fast & mid above slow ────────────────────────
        Decision ema = emaStackCheck(ticker);
        if (!ema.allowed()) return ema;

        log.info("[EntryGuard] PASS ticker={} — 30m ST up, EMA stack ok", ticker);
        return Decision.allow();
    }

    // -----------------------------------------------------------------------
    // Supertrend per timeframe (bars come from the shared BarDataManager)
    // -----------------------------------------------------------------------

    /** Latest-completed-bar Supertrend check for one timeframe → allow/block Decision. */
    private Decision supertrendCheck(String ticker, String timespan) {
        Direction dir = computeLatestSupertrend(ticker, timespan);
        if (dir == null) {
            return Decision.block("ST_UNAVAILABLE_" + timespan);
        }
        if (dir != Direction.UP) {
            return Decision.block("ST_NOT_UP_" + timespan + " (dir=" + dir + ")");
        }
        return Decision.allow();
    }

    /** Computes the latest COMPLETED-bar Supertrend direction, or null if unavailable. */
    private Direction computeLatestSupertrend(String ticker, String timespan) {
        int len = supertrend.length();
        // Need enough bars for a stable ATR seed; request a healthy window.
        int count = Math.max(len * 10, 120);
        List<Candle> bars = barData.getBars(ticker, timespan, count);
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

        // Slowest EMA needs the most history; request slow + a buffer.
        int count = slow + 100;
        List<Candle> bars = barData.getBars(ticker, tf, count);
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
}
