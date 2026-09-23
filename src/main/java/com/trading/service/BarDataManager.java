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

    private final MarketDataService marketDataService;
    private final com.trading.config.WebullProperties props;

    // Rate limiter state: the earliest wall-clock time (ms) the next fetch may run.
    // Spacing = 1000 / maxRequestsPerSecond ms between fetches, enforced across all threads.
    private final Object rateLock = new Object();
    private long nextAllowedMs = 0L;

    public BarDataManager(MarketDataService marketDataService,
                          com.trading.config.WebullProperties props) {
        this.marketDataService = marketDataService;
        this.props = props;
    }

    private record Cached(List<Candle> bars, int count, long boundary) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Blocks until this thread is allowed to make a Webull fetch, spacing calls to at
     * most {@code webull.trading.max-requests-per-second}. A no-op when the config is
     * 0 (unlimited). Only gates ACTUAL fetches — cache hits never call this.
     */
    private void throttle() {
        int rps = props.trading().maxRequestsPerSecond();
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
        String key = ticker + "|" + tf;
        long boundary = currentBoundary(tf);

        Cached existing = cache.get(key);
        boolean fresh = existing != null
                && existing.boundary() == boundary
                && existing.count() >= minCount;
        if (fresh) {
            return existing.bars();
        }

        // Fetch once per (ticker, tf, boundary). Synchronise per key so concurrent
        // readers of the same key don't all hit Webull.
        synchronized (key.intern()) {
            existing = cache.get(key);
            if (existing != null && existing.boundary() == boundary && existing.count() >= minCount) {
                return existing.bars();
            }
            try {
                throttle();   // space out actual Webull calls to respect the rate limit
                List<Candle> bars = marketDataService.fetchHistoricalBars(ticker, minCount, tf, null);
                cache.put(key, new Cached(bars, minCount, boundary));
                log.debug("[BarDataManager] Fetched {} {} bars for {} (boundary={})",
                        bars.size(), tf, ticker, boundary);
                return bars;
            } catch (Exception e) {
                log.warn("[BarDataManager] Fetch failed for {} {}: {}", ticker, tf, e.getMessage());
                // Fall back to any stale cache rather than nothing.
                return existing != null ? existing.bars() : List.of();
            }
        }
    }

    /** Warms the cache for a ticker/timeframe on startup (initial load). */
    public void preload(String ticker, String timeframe, int count) {
        getBars(ticker, timeframe, count);
    }

    /** Clears the entire bar cache (e.g. daily reset). */
    public void clear() {
        cache.clear();
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

    /** Minutes per bar for minute-based timeframes; 0 for D/W/M/Y. */
    private static int timeframeMinutes(String tf) {
        return switch (tf) {
            case "M1"   -> 1;
            case "M5"   -> 5;
            case "M15"  -> 15;
            case "M30"  -> 30;
            case "M60"  -> 60;
            case "M120" -> 120;
            case "M240" -> 240;
            default     -> 0;   // D, W, M, Y
        };
    }
}
