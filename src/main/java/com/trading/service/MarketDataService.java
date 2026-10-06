package com.trading.service;

import com.trading.broker.BrokerClient;
import com.trading.broker.BrokerException;
import com.trading.config.WebullProperties;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fetches candlestick bar data from the broker's data API via the
 * {@link BrokerClient} abstraction and returns the typed, broker-neutral
 * {@link Candle} model.
 *
 * <h2>Responsibilities</h2>
 * <p>This service owns the application's <em>canonical</em> timespan/session
 * vocabulary and validation ({@link #normaliseTimespan}, {@link #normaliseSessions}),
 * the default-sessions policy from config, and the oldest-first ordering contract.
 * All broker-specific concerns — HTTP, JSON parsing, market category, and any
 * broker-specific timespan/session translation — live behind {@link BrokerClient}
 * in the broker adapter. Broker call failures surface as {@link MarketDataException}
 * so the endpoint layer sees a stable exception type.</p>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** Canonical timespan code for 1-minute bars (default used by the trading loop). */
    private static final String TIMESPAN_1M = "M1";

    /** Canonical timespans the application supports (adapters translate as needed). */
    public static final java.util.Set<String> SUPPORTED_TIMESPANS = java.util.Set.of(
            "M1", "M5", "M15", "M30", "M60", "M120", "M240", "D", "W", "M", "Y");

    /** Canonical trading sessions the application supports. */
    public static final java.util.Set<String> SUPPORTED_SESSIONS = java.util.Set.of(
            "RTH", "PRE", "ATH", "OVN");

    /**
     * Validates and normalises a comma-separated trading-sessions string against
     * {@link #SUPPORTED_SESSIONS}.
     *
     * @param sessions e.g. {@code "RTH"} or {@code "PRE,RTH,ATH"}; null/blank returns null
     * @return normalised uppercase comma-joined sessions, or null when input is blank
     * @throws IllegalArgumentException if any session token is unsupported
     */
    public static String normaliseSessions(String sessions) {
        if (sessions == null || sessions.isBlank()) return null;
        String[] parts = sessions.trim().toUpperCase().split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (!SUPPORTED_SESSIONS.contains(s)) {
                throw new IllegalArgumentException(
                        "Unsupported trading session '" + s + "'. Supported: " + SUPPORTED_SESSIONS);
            }
            if (!out.contains(s)) out.add(s);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /**
     * Validates and normalises a timespan string against {@link #SUPPORTED_TIMESPANS}.
     *
     * @param timespan requested timespan (case-insensitive), or null/blank for default M1
     * @return the normalised uppercase timespan
     * @throws IllegalArgumentException if the value is not a supported timespan
     */
    public static String normaliseTimespan(String timespan) {
        if (timespan == null || timespan.isBlank()) return TIMESPAN_1M;
        String t = timespan.trim().toUpperCase();
        if (!SUPPORTED_TIMESPANS.contains(t)) {
            throw new IllegalArgumentException(
                    "Unsupported timespan '" + timespan + "'. Supported: " + SUPPORTED_TIMESPANS);
        }
        return t;
    }

    private final WebullProperties props;
    private final BrokerClient client;

    public MarketDataService(WebullProperties props, BrokerClient client) {
        this.props = props;
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // Historical bars — warm-up (throws on broker error)
    // -----------------------------------------------------------------------

    /**
     * Fetches {@code count} historical 1-minute bars for {@code ticker}.
     * Returns an unmodifiable list oldest-first. Throws {@link MarketDataException}
     * when the broker call fails, so the endpoint layer can surface the real error.
     */
    public List<Candle> fetchHistoricalBars(String ticker, int count) {
        return fetchHistoricalBars(ticker, count, TIMESPAN_1M, null);
    }

    /** Timespan overload — uses the configured default trading sessions. */
    public List<Candle> fetchHistoricalBars(String ticker, int count, String timespan) {
        return fetchHistoricalBars(ticker, count, timespan, null);
    }

    /**
     * Fetches {@code count} historical bars for {@code ticker} at the given
     * {@code timespan} and trading {@code sessions}. Returns oldest-first.
     *
     * @param ticker   equity symbol
     * @param count    number of bars
     * @param timespan bar granularity — must be one of {@link #SUPPORTED_TIMESPANS}
     * @param sessions comma-separated sessions (RTH,PRE,ATH,OVN); when null/blank the
     *                 configured {@code webull.trading.trading-sessions} default is used
     * @throws IllegalArgumentException if timespan or a session is unsupported
     * @throws MarketDataException      if the broker call fails
     */
    public List<Candle> fetchHistoricalBars(String ticker, int count, String timespan, String sessions) {
        String ts = normaliseTimespan(timespan);
        // Per-request sessions override the configured default; both are validated.
        String requested = (sessions != null && !sessions.isBlank())
                ? sessions
                : props.trading().tradingSessions();
        String sess = normaliseSessions(requested);

        log.info("[MarketDataService] Fetching {} {} bars for ticker={} sessions={}",
                count, ts, ticker, sess);

        List<Candle> candles;
        try {
            // Adapter returns broker-order (newest-first) neutral candles.
            candles = new ArrayList<>(client.fetchBars(ticker, count, ts, sess));
        } catch (BrokerException e) {
            throw new MarketDataException(e.getMessage());
        }

        if (candles.isEmpty()) {
            log.warn("[MarketDataService] No bars parsed for ticker={} timespan={}", ticker, ts);
            return Collections.emptyList();
        }
        Collections.reverse(candles); // oldest-first
        log.info("[MarketDataService] Fetched {} {} candles for ticker={}", candles.size(), ts, ticker);
        return Collections.unmodifiableList(candles);
    }

    /**
     * Non-throwing variant used by the scheduled trading loop — returns empty on any error.
     */
    public List<Candle> fetchHistoricalBarsQuietly(String ticker, int count) {
        try {
            return fetchHistoricalBars(ticker, count);
        } catch (Exception e) {
            log.debug("[MarketDataService] Quiet fetch failed for ticker={}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches bars for MANY symbols in a single broker request (one call for the
     * whole batch — the rate-limit-friendly path). Returns a map of symbol →
     * oldest-first candles. Symbols with no data are simply absent from the map.
     *
     * @param symbols  tickers to fetch (batched into one request)
     * @param count    bars per symbol
     * @param timespan bar granularity
     * @param sessions comma-separated sessions, or null for the configured default
     * @throws MarketDataException on a failed broker call
     */
    public Map<String, List<Candle>> fetchHistoricalBarsBatch(List<String> symbols, int count,
                                                              String timespan, String sessions) {
        Map<String, List<Candle>> out = new java.util.LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        String ts = normaliseTimespan(timespan);
        String requested = (sessions != null && !sessions.isBlank()) ? sessions : props.trading().tradingSessions();
        String sess = normaliseSessions(requested);

        log.info("[MarketDataService] Batch-fetching {} {} bars for {} symbols {}",
                count, ts, symbols.size(), symbols);

        Map<String, List<Candle>> bySymbol;
        try {
            // Adapter returns broker-order (newest-first) neutral candles per symbol.
            bySymbol = client.fetchBarsBatch(symbols, count, ts, sess);
        } catch (BrokerException e) {
            throw new MarketDataException(e.getMessage());
        }

        for (Map.Entry<String, List<Candle>> e : bySymbol.entrySet()) {
            List<Candle> candles = new ArrayList<>(e.getValue());
            if (candles.isEmpty()) continue;
            Collections.reverse(candles);   // oldest-first
            out.put(e.getKey(), Collections.unmodifiableList(candles));
        }
        log.info("[MarketDataService] Batch fetched {} of {} symbols ({} {})",
                out.size(), symbols.size(), ts, count);
        return out;
    }

    // -----------------------------------------------------------------------
    // Latest bar — live polling
    // -----------------------------------------------------------------------

    /**
     * Fetches the most recently completed 1-minute bar for {@code ticker}.
     * Returns {@code null} on any failure so the orchestrator can skip the tick.
     */
    public Candle fetchLatestBar(String ticker) {
        List<Candle> candles = client.fetchRecentBars(ticker, 2);   // newest-first, non-throwing
        if (candles == null || candles.isEmpty()) return null;
        // index 1 is the last completed bar (index 0 may be the in-progress bar)
        return candles.size() >= 2 ? candles.get(1) : candles.get(0);
    }
}
