package com.trading.service;

import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single owner of all historical-bar fetching. Every consumer (strategies, entry
 * guard, exit manager, controllers) reads bars from here instead of calling
 * {@link MarketDataService} directly, so Webull is hit at most once per
 * (ticker, timeframe) per bar interval.
 *
 * <h2>Caching model</h2>
 * <ul>
 *   <li>Cache key = {@code ticker|timeframe}; value = the fetched bars (oldest-first)
 *       plus the bar-boundary at which they were fetched and how many were fetched.</li>
 *   <li>{@link #getBars} is <b>read-through</b>: it returns cached bars and only
 *       re-fetches from Webull when the data is <b>missing</b>, <b>too few</b> for the
 *       requested count, or the timeframe's <b>bar boundary has advanced</b> since the
 *       last fetch (M1 → new minute; M30 → new :00/:30; etc.).</li>
 *   <li>History is retained between calls — a still-current cache is reused.</li>
 * </ul>
 */
@Service
public class BarDataManager {

    private static final Logger log = LoggerFactory.getLogger(BarDataManager.class);

    /** Webull's bars endpoint accepts at most 20 symbols per request. */
    private static final int MAX_BATCH_SYMBOLS = 20;

    private final MarketDataService marketDataService;
    private final com.trading.config.AppProperties props;

    // Rate limiter state: the earliest wall-clock time (ms) the next fetch may run.
    // Spacing = 1000 / maxRequestsPerSecond ms between fetches, enforced across all threads.
    private final Object rateLock = new Object();
    private long nextAllowedMs = 0L;

    public BarDataManager(MarketDataService marketDataService,
                          com.trading.config.AppProperties props) {
        this.marketDataService = marketDataService;
        this.props = props;
    }

    private record Cached(List<Candle> bars, int count, long boundary) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> recoveryBoundary = new ConcurrentHashMap<>();

    /** Targeted recovery used by HA; existing consumers retain their normal cache behavior. */
    public List<Candle> refreshBars(String ticker, String timeframe, int minCount) {
        String tf = MarketDataService.normaliseTimespan(timeframe);
        String key = ticker + "|" + tf;
        synchronized (key.intern()) {
            long boundary = currentBoundary(tf);
            long recoverySlot = Instant.now().getEpochSecond() / 60L;
            Cached old = cache.get(key);
            if (java.util.Objects.equals(recoveryBoundary.get(key), recoverySlot)) {
                return old == null ? List.of() : tail(old.bars(), minCount);
            }
            recoveryBoundary.put(key, recoverySlot);
            cache.remove(key);
            String sourceKey = ticker + "|M1";
            Cached oldSource = TimeframeSupport.isSynthetic(tf) ? cache.remove(sourceKey) : null;
            List<Candle> fresh = getBars(ticker, tf, minCount);
            if (oldSource != null) {
                cache.compute(sourceKey, (ignored, current) -> current == null || current.bars().isEmpty() ? oldSource : current);
            }
            if ((fresh == null || fresh.isEmpty()) && old != null) {
                cache.compute(key, (ignored, current) -> current == null || current.bars().isEmpty() ? old : current);
                return tail(old.bars(), minCount);
            }
            return fresh;
        }
    }

    /**
     * Blocks until this thread is allowed to make a Webull fetch, spacing calls to at
     * most {@code webull.trading.max-requests-per-second}. A no-op when the config is
     * 0 (unlimited). Only gates ACTUAL fetches — cache hits never call this.
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
     * Returns bars for {@code ticker} at {@code timeframe} (oldest-first), reusing the
     * cache when it is current, else fetching fresh from Webull.
     *
     * @param ticker    equity symbol
     * @param timeframe Webull timespan (M1, M5, M15, M30, M60, M120, M240, D, W, M, Y)
     * @param minCount  minimum number of bars needed by the caller
     * @return bars (possibly empty on failure with no prior cache)
     */
    public List<Candle> getBars(String ticker, String timeframe, int minCount) {
        String tf = MarketDataService.normaliseTimespan(timeframe);

        // Synthetic minute timeframe (M2/M3/…): build it from CACHED M1 so the underlying M1 is
        // fetched once and shared with M1 consumers and every other synthetic timeframe — no
        // duplicate broker call. The resampled Mn series is itself cached under ticker|tf.
        if (TimeframeSupport.isSynthetic(tf)) {
            return getSyntheticBars(ticker, tf, minCount);
        }

        String key = ticker + "|" + tf;
        long boundary = currentBoundary(tf);

        // Normalise the fetch size: always fetch the STANDARD bar count (>= any
        // caller's minCount) so different callers (count=2 / 200 / 700) all share ONE
        // cached series per (ticker, tf, boundary) instead of triggering separate
        // fetches. Smaller requests are served as a tail sublist of the cached series.
        int fetchCount = Math.max(minCount, standardBarCount());

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
                List<Candle> bars = marketDataService.fetchHistoricalBars(ticker, fetchCount, tf, null);
                cache.put(key, new Cached(bars, fetchCount, boundary));
                log.debug("[BarDataManager] Fetched {} {} bars for {} (boundary={})",
                        bars.size(), tf, ticker, boundary);
                return tail(bars, minCount);
            } catch (Exception e) {
                log.warn("[BarDataManager] Fetch failed for {} {}: {}", ticker, tf, e.getMessage());
                return existing != null ? tail(existing.bars(), minCount) : List.of();
            }
        }
    }

    /**
     * Serves a synthetic minute timeframe (M2/M3/…) by reading the underlying native M1 series
     * THROUGH THIS CACHE (so M1 is fetched at most once per boundary and reused across M1 and
     * all synthetic consumers), resampling it, and caching the result under {@code ticker|tf}.
     *
     * <p>The resampled series is cached against the M3 boundary so repeated reads within the
     * same M3 bar are cache hits; it re-resamples when the M3 bar boundary advances. Each Mn bar
     * needs {@code minutes} M1 bars, so we ask the M1 cache for {@code minCount * minutes}.</p>
     */
    private List<Candle> getSyntheticBars(String ticker, String tf, int minCount) {
        int minutes = TimeframeSupport.minutesOf(tf);
        String key = ticker + "|" + tf;
        long boundary = currentBoundary(tf);

        // Fast path: resampled series already current for this Mn boundary.
        Cached existing = cache.get(key);
        if (existing != null && existing.boundary() == boundary && existing.count() >= minCount) {
            return tail(existing.bars(), minCount);
        }

        synchronized (key.intern()) {
            existing = cache.get(key);
            if (existing != null && existing.boundary() == boundary && existing.count() >= minCount) {
                return tail(existing.bars(), minCount);
            }
            // Read the underlying M1 through the cache (shared, deduplicated fetch), then resample.
            int m1Needed = Math.min(TimeframeSupport.BROKER_MAX_BARS, Math.max(minCount * minutes, minutes));
            List<Candle> m1 = getBars(ticker, "M1", m1Needed);
            List<Candle> resampled = BarResampler.resample(m1, minutes);
            if (!resampled.isEmpty()) {
                cache.put(key, new Cached(resampled, resampled.size(), boundary));
            }
            log.debug("[BarDataManager] Resampled {} {} bars for {} from {} cached M1 (boundary={})",
                    resampled.size(), tf, ticker, m1.size(), boundary);
            return tail(resampled, minCount);
        }
    }

    /** The standard bar count fetched/cached per series (covers the largest consumer, the 600-EMA). */
    private int standardBarCount() {
        return Math.max(props.engine().warmupBars(), 700);
    }

    /** Returns the last {@code n} bars of {@code bars} (or all if fewer). */
    private static List<Candle> tail(List<Candle> bars, int n) {
        if (bars == null) return List.of();
        if (n >= bars.size()) return bars;
        return bars.subList(bars.size() - n, bars.size());
    }

    /**
     * Refreshes the cache for MANY tickers at one timeframe using a SINGLE multi-symbol
     * Webull request (instead of one call per ticker). Only tickers whose cache is
     * stale for the current bar boundary (or short of {@code minCount}) are fetched;
     * already-fresh tickers are skipped. Call this once per timeframe per tick; then
     * consumers read individual tickers via {@link #getBars} (cache hits, no I/O).
     *
     * @param tickers   symbols to refresh
     * @param timeframe Webull timespan
     * @param minCount  bars per symbol
     */
    public void getBarsBatch(List<String> tickers, String timeframe, int minCount) {
        if (tickers == null || tickers.isEmpty()) return;
        String tf = MarketDataService.normaliseTimespan(timeframe);

        // Synthetic minute timeframe (M2/M3/…): batch-refresh the underlying native M1 ONCE
        // (shared cache, one multi-symbol call), then resample+cache each symbol's Mn series.
        // This reuses the M1 fetch for M1 consumers and every synthetic timeframe.
        if (TimeframeSupport.isSynthetic(tf)) {
            getSyntheticBarsBatch(tickers, tf, minCount);
            return;
        }

        long boundary = currentBoundary(tf);
        // Normalise to the standard series size so batch-cached entries satisfy every
        // consumer (count=2/200/700) from one fetch.
        int fetchCount = Math.max(minCount, standardBarCount());

        // Only fetch the tickers that are actually stale for this boundary.
        List<String> stale = new java.util.ArrayList<>();
        for (String ticker : tickers) {
            Cached existing = cache.get(ticker + "|" + tf);
            boolean fresh = existing != null && existing.boundary() == boundary && existing.count() >= fetchCount;
            if (!fresh) stale.add(ticker);
        }
        if (stale.isEmpty()) {
            return;   // everything already current for this boundary
        }

        // Webull caps the bars endpoint at 20 symbols per request — chunk accordingly.
        for (int i = 0; i < stale.size(); i += MAX_BATCH_SYMBOLS) {
            List<String> chunk = stale.subList(i, Math.min(i + MAX_BATCH_SYMBOLS, stale.size()));
            try {
                throttle();   // one throttled call per chunk
                Map<String, List<Candle>> bySymbol =
                        marketDataService.fetchHistoricalBarsBatch(chunk, fetchCount, tf, null);
                for (String ticker : chunk) {
                    List<Candle> bars = bySymbol.get(ticker.toUpperCase());
                    if (bars != null) {
                        cache.put(ticker + "|" + tf, new Cached(bars, fetchCount, boundary));
                    }
                    // Symbols missing from the response keep any prior cache; getBars
                    // will fall back to it or fetch singly on demand.
                }
                log.debug("[BarDataManager] Batch-refreshed {} {} of {} in chunk (boundary={})",
                        bySymbol.size(), tf, chunk.size(), boundary);
            } catch (Exception e) {
                log.warn("[BarDataManager] Batch fetch failed for {} chunk {}: {}", tf, chunk, e.getMessage());
                // Leave existing cache in place; getBars will retry per-ticker if needed.
            }
        }
    }

    /**
     * Batch variant of {@link #getSyntheticBars}: refreshes the underlying native M1 for all
     * tickers through {@link #getBarsBatch} (one shared, deduplicated multi-symbol fetch), then
     * resamples each ticker's M1 to {@code tf} and caches the result under {@code ticker|tf}.
     */
    private void getSyntheticBarsBatch(List<String> tickers, String tf, int minCount) {
        int minutes = TimeframeSupport.minutesOf(tf);
        long boundary = currentBoundary(tf);

        // Only resample tickers whose Mn cache is stale for this boundary.
        List<String> stale = new java.util.ArrayList<>();
        for (String ticker : tickers) {
            Cached existing = cache.get(ticker + "|" + tf);
            boolean fresh = existing != null && existing.boundary() == boundary && existing.count() >= minCount;
            if (!fresh) stale.add(ticker);
        }
        if (stale.isEmpty()) return;

        // Refresh the shared M1 cache for the stale tickers in one batched call.
        int m1Needed = Math.min(TimeframeSupport.BROKER_MAX_BARS, Math.max(minCount * minutes, minutes));
        getBarsBatch(stale, "M1", m1Needed);

        // Resample each ticker from its (now cached) M1 and cache the Mn series.
        for (String ticker : stale) {
            List<Candle> m1 = getBars(ticker, "M1", m1Needed);
            List<Candle> resampled = BarResampler.resample(m1, minutes);
            if (!resampled.isEmpty()) {
                cache.put(ticker + "|" + tf, new Cached(resampled, resampled.size(), boundary));
            }
        }
        log.debug("[BarDataManager] Batch-resampled {} tickers to {} from cached M1 (boundary={})",
                stale.size(), tf, boundary);
    }

    /** Warms the cache for a ticker/timeframe on startup (initial load). */
    public void preload(String ticker, String timeframe, int count) {
        getBars(ticker, timeframe, count);
    }

    /** Clears the entire bar cache (e.g. daily reset). */
    public void clear() {
        cache.clear();
        recoveryBoundary.clear();
    }

    /**
     * The epoch-second of the current bar boundary for a timeframe — i.e. the start
     * of the bar currently forming. When this changes, a new bar has begun and the
     * cache for that timeframe is considered stale.
     */
    private static long currentBoundary(String tf) {
        long nowSec = Instant.now().getEpochSecond();
        int minutes = timeframeMinutes(tf);
        if (minutes > 0) {
            long windowSec = minutes * 60L;
            return (nowSec / windowSec) * windowSec;
        }
        // Day/Week/Month/Year — coarse: refresh at most once per UTC day.
        return (nowSec / 86400L) * 86400L;
    }

    /**
     * Minutes per bar for minute-based timeframes; 0 for D/W/M/Y. Parses any {@code M<n>}
     * minute form generically (so synthetic M2/M3 get the correct, finer cache boundary and
     * refresh on their own bar cadence rather than falling back to the coarse daily boundary).
     */
    private static int timeframeMinutes(String tf) {
        int minutes = TimeframeSupport.minutesOf(tf);
        return Math.max(minutes, 0);   // -1 (D/W/M/Y) → 0
    }
}
