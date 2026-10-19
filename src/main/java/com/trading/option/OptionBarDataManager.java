package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single owner of all OPTION historical-bar fetching — the options mirror of
 * {@link com.trading.service.BarDataManager}. Every options consumer (the contract
 * loader, the ST/EMA/history endpoints) reads bars from here instead of calling
 * {@link MarketDataService} directly, so Webull is hit at most once per
 * (occSymbol, timeframe) per bar interval.
 *
 * <h2>Caching model (identical to the stock BarDataManager)</h2>
 * <ul>
 *   <li>Cache key = {@code occSymbol|timeframe}; value = the fetched bars (oldest-first)
 *       plus the bar-boundary at which they were fetched and how many were fetched.</li>
 *   <li>{@link #getBars} is <b>read-through</b>: returns cached bars and only re-fetches
 *       when missing, too few for the requested count, or the timeframe's bar boundary
 *       has advanced (M1 → new minute; M5 → new :00/:05; etc.).</li>
 *   <li>A still-current cache is reused between calls.</li>
 *   <li>Fetches are throttled with the SAME rate limiter config
 *       ({@code app.engine.max-requests-per-second}) and batched 20/request.</li>
 * </ul>
 *
 * <h2>Differences from the stock manager</h2>
 * Options are fetched via {@link MarketDataService#fetchOptionBars} /
 * {@link MarketDataService#fetchOptionBarsBatch} (category US_OPTION), there is no
 * synthetic M2/M3 resampling (options are native-only), no trading-sessions filter,
 * and the broker caps option bars at 1200/request (vs 1650 for stocks). The standard
 * series size is {@code app.options.bar-count}.
 */
@Service
public class OptionBarDataManager {

    private static final Logger log = LoggerFactory.getLogger(OptionBarDataManager.class);

    /** Webull's option bars endpoint accepts at most 20 symbols per request. */
    private static final int MAX_BATCH_SYMBOLS = 20;

    /** Webull's per-request cap for OPTION bars (count &gt; this → HTTP 417). */
    private static final int OPTION_BARS_MAX = 1200;

    private final MarketDataService marketDataService;
    private final AppProperties props;

    // Rate limiter state: the earliest wall-clock time (ms) the next fetch may run.
    private final Object rateLock = new Object();
    private long nextAllowedMs = 0L;

    public OptionBarDataManager(MarketDataService marketDataService, AppProperties props) {
        this.marketDataService = marketDataService;
        this.props = props;
    }

    private record Cached(List<Candle> bars, int count, long boundary) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> recoveryBoundary = new ConcurrentHashMap<>();

    /** One additional throttled fetch per key/minute, bypassing an unsuitable cache. */
    public List<Candle> refreshBars(String symbol, String timeframe, int minCount) {
        String tf = MarketDataService.normaliseTimespan(timeframe);
        String occ = symbol.trim().toUpperCase();
        String key = occ + "|" + tf;
        synchronized (key.intern()) {
            long boundary = currentBoundary(tf);
            long recoverySlot = Instant.now().getEpochSecond() / 60L;
            Cached old = cache.get(key);
            if (java.util.Objects.equals(recoveryBoundary.get(key), recoverySlot)) {
                return old == null ? List.of() : tail(old.bars(), minCount);
            }
            recoveryBoundary.put(key, recoverySlot);
            int count = Math.min(OPTION_BARS_MAX, Math.max(minCount, standardBarCount()));
            try {
                throttle();
                List<Candle> fresh = marketDataService.fetchOptionBars(occ, count, tf);
                if (fresh != null && !fresh.isEmpty()) {
                    cache.put(key, new Cached(fresh, count, boundary));
                    return tail(fresh, minCount);
                }
            } catch (Exception e) {
                log.warn("[OptionBarDataManager] Recovery failed for {} {}: {}", occ, tf, e.getMessage());
            }
            // Retain old history for diagnostics; signal consumers must validate timestamps.
            return old == null ? List.of() : tail(old.bars(), minCount);
        }
    }

    /**
     * Blocks until this thread is allowed to make a Webull fetch, spacing calls to at
     * most {@code app.engine.max-requests-per-second} (shared rate budget with the rest
     * of the app). No-op when 0 (unlimited). Only gates ACTUAL fetches — cache hits don't.
     */
    private void throttle() {
        int rps = props.engine().maxRequestsPerSecond();
        if (rps <= 0) return;
        long spacingMs = 1000L / rps;
        long waitMs;
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedMs);
            waitMs = slot - now;
            nextAllowedMs = slot + spacingMs;
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns bars for one OPTION contract at {@code timeframe} (oldest-first), reusing
     * the cache when current, else fetching fresh from Webull.
     *
     * @param occSymbol OCC option symbol (e.g. AAPL260522C00300000)
     * @param timeframe native Webull timespan (M1/M5/M15/M30/M60/M120/M240/D/W/M/Y)
     * @param minCount  minimum number of bars the caller needs
     * @return bars (empty on failure with no prior cache)
     */
    public List<Candle> getBars(String occSymbol, String timeframe, int minCount) {
        String tf = MarketDataService.normaliseTimespan(timeframe);
        String occ = occSymbol.trim().toUpperCase();
        String key = occ + "|" + tf;
        long boundary = currentBoundary(tf);

        // Always fetch the STANDARD series size (>= caller's minCount) so different callers
        // share one cached series per (occ, tf, boundary); smaller requests get a tail.
        int fetchCount = Math.min(OPTION_BARS_MAX, Math.max(minCount, standardBarCount()));

        Cached existing = cache.get(key);
        if (existing != null && existing.boundary() == boundary && existing.count() >= minCount) {
            return tail(existing.bars(), minCount);
        }

        synchronized (key.intern()) {
            existing = cache.get(key);
            if (existing != null && existing.boundary() == boundary && existing.count() >= minCount) {
                return tail(existing.bars(), minCount);
            }
            try {
                throttle();
                List<Candle> bars = marketDataService.fetchOptionBars(occ, fetchCount, tf);
                cache.put(key, new Cached(bars, fetchCount, boundary));
                log.debug("[OptionBarDataManager] Fetched {} {} bars for {} (boundary={})",
                        bars.size(), tf, occ, boundary);
                return tail(bars, minCount);
            } catch (Exception e) {
                log.warn("[OptionBarDataManager] Fetch failed for {} {}: {}", occ, tf, e.getMessage());
                return existing != null ? tail(existing.bars(), minCount) : List.of();
            }
        }
    }

    /**
     * Refreshes the cache for MANY option contracts at one timeframe using single
     * multi-symbol requests (20/chunk). Only contracts whose cache is stale for the
     * current boundary (or short of {@code minCount}) are fetched. Call this once per
     * load; consumers then read individual contracts via {@link #getBars} (cache hits).
     *
     * @param occSymbols OCC symbols to refresh
     * @param timeframe  native Webull timespan
     * @param minCount   bars per contract
     */
    public void getBarsBatch(List<String> occSymbols, String timeframe, int minCount) {
        if (occSymbols == null || occSymbols.isEmpty()) return;
        String tf = MarketDataService.normaliseTimespan(timeframe);
        long boundary = currentBoundary(tf);
        int fetchCount = Math.min(OPTION_BARS_MAX, Math.max(minCount, standardBarCount()));

        // Only fetch contracts that are actually stale for this boundary.
        List<String> stale = new ArrayList<>();
        for (String occRaw : occSymbols) {
            String occ = occRaw.trim().toUpperCase();
            Cached existing = cache.get(occ + "|" + tf);
            boolean fresh = existing != null && existing.boundary() == boundary && existing.count() >= fetchCount;
            if (!fresh) stale.add(occ);
        }
        if (stale.isEmpty()) return;

        for (int i = 0; i < stale.size(); i += MAX_BATCH_SYMBOLS) {
            List<String> chunk = stale.subList(i, Math.min(i + MAX_BATCH_SYMBOLS, stale.size()));
            try {
                throttle();   // one throttled call per chunk
                Map<String, List<Candle>> bySymbol =
                        marketDataService.fetchOptionBarsBatch(chunk, fetchCount, tf);
                for (String occ : chunk) {
                    List<Candle> bars = bySymbol.get(occ);
                    if (bars != null) {
                        cache.put(occ + "|" + tf, new Cached(bars, fetchCount, boundary));
                    }
                    // Missing symbols keep any prior cache; getBars falls back / fetches singly.
                }
                log.debug("[OptionBarDataManager] Batch-refreshed {} {} of {} in chunk (boundary={})",
                        bySymbol.size(), tf, chunk.size(), boundary);
            } catch (Exception e) {
                log.warn("[OptionBarDataManager] Batch fetch failed for {} chunk {}: {}", tf, chunk, e.getMessage());
                // Leave existing cache in place; getBars will retry per-contract if needed.
            }
        }
    }

    /** Clears the entire option-bar cache (e.g. daily reset). */
    public void clear() {
        cache.clear();
        recoveryBoundary.clear();
    }

    /** The standard series size fetched/cached per contract — the configured option bar count. */
    private int standardBarCount() {
        int configured = props.options().barCount();
        return configured > 0 ? configured : 200;
    }

    /** Returns the last {@code n} bars of {@code bars} (or all if fewer). */
    private static List<Candle> tail(List<Candle> bars, int n) {
        if (bars == null) return List.of();
        if (n >= bars.size()) return bars;
        return bars.subList(bars.size() - n, bars.size());
    }

    /**
     * The epoch-second of the current bar boundary for a timeframe — the start of the bar
     * currently forming. When this advances, a new bar has begun and the cache for that
     * timeframe is stale. (Identical to the stock manager's boundary math.)
     */
    private static long currentBoundary(String tf) {
        long nowSec = Instant.now().getEpochSecond();
        int minutes = Math.max(com.trading.service.TimeframeSupport.minutesOf(tf), 0);
        if (minutes > 0) {
            long windowSec = minutes * 60L;
            return (nowSec / windowSec) * windowSec;
        }
        // Day/Week/Month/Year — coarse: refresh at most once per UTC day.
        return (nowSec / 86400L) * 86400L;
    }
}
