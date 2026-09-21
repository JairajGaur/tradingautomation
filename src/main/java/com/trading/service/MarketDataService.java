package com.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.model.Candle;
import com.trading.webull.WebullV3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches candlestick bar data from the Webull <b>v3</b> Data API via
 * {@link WebullV3Client} ({@code GET /market-data/bars}) and maps the JSON into
 * the typed {@link Candle} model.
 *
 * <h2>Params</h2>
 * <ul>
 *   <li>{@code category = US_STOCK}</li>
 *   <li>{@code timespan = M1} (1-minute bars)</li>
 *   <li>{@code count} — number of bars</li>
 * </ul>
 *
 * <p>The v3 bars response returns newest-first; this service reverses to
 * chronological (oldest-first) after mapping.</p>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** v3 timespan code for 1-minute bars (default used by the trading loop). */
    private static final String TIMESPAN_1M = "M1";
    /** v3 market category for US equities. */
    private static final String CATEGORY_US_STOCK = "US_STOCK";

    /** Timespans Webull's stock bars endpoint accepts. */
    public static final java.util.Set<String> SUPPORTED_TIMESPANS = java.util.Set.of(
            "M1", "M5", "M15", "M30", "M60", "M120", "M240", "D", "W", "M", "Y");

    /** Trading sessions Webull's bars endpoint accepts. */
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
    private final WebullV3Client client;

    public MarketDataService(WebullProperties props, WebullV3Client client) {
        this.props = props;
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // Historical bars — warm-up (throws on Webull error)
    // -----------------------------------------------------------------------

    /**
     * Fetches {@code count} historical 1-minute bars for {@code ticker}.
     * Returns an unmodifiable list oldest-first. Throws {@link MarketDataException}
     * when the v3 call fails, so the endpoint layer can surface the real error.
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
     * @throws MarketDataException      if the Webull call fails
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

        WebullV3Client.V3Response resp = client.bars(ticker, CATEGORY_US_STOCK, ts, count, sess);
        if (!resp.success()) {
            throw new MarketDataException(
                    "bars request failed for " + ticker + " timespan=" + ts + " sessions=" + sess
                    + " (status=" + resp.statusCode() + ", body=" + resp.rawBody() + ")");
        }

        List<Candle> candles = parseBars(ticker, resp.body());
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

    // -----------------------------------------------------------------------
    // Latest bar — live polling
    // -----------------------------------------------------------------------

    /**
     * Fetches the most recently completed 1-minute bar for {@code ticker}.
     * Returns {@code null} on any failure so the orchestrator can skip the tick.
     */
    public Candle fetchLatestBar(String ticker) {
        try {
            WebullV3Client.V3Response resp = client.bars(ticker, CATEGORY_US_STOCK, TIMESPAN_1M, 2);
            if (!resp.success()) {
                log.warn("[MarketDataService] latest bar call failed for ticker={} status={}",
                        ticker, resp.statusCode());
                return null;
            }
            List<Candle> candles = parseBars(ticker, resp.body());  // newest-first
            if (candles.isEmpty()) return null;
            // index 1 is the last completed bar (index 0 may be the in-progress bar)
            return candles.size() >= 2 ? candles.get(1) : candles.get(0);
        } catch (Exception e) {
            log.error("[MarketDataService] Exception fetching latest bar for ticker={}", ticker, e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // JSON parsing
    // -----------------------------------------------------------------------

    /**
     * Parses the v3 bars JSON into candles (in the order returned — newest-first).
     *
     * <p>The v3 batch-bars response is typically an array of per-symbol objects,
     * each containing a {@code bars} array. We also handle a bare {@code bars}
     * array at the top level.</p>
     */
    private List<Candle> parseBars(String ticker, JsonNode root) {
        List<Candle> out = new ArrayList<>();
        if (root == null) return out;

        JsonNode barsArray = locateBarsArray(root);
        if (barsArray == null || !barsArray.isArray()) {
            log.debug("[MarketDataService] Could not locate bars array for ticker={} in: {}", ticker, root);
            return out;
        }

        for (JsonNode bar : barsArray) {
            Candle c = mapBar(ticker, bar);
            if (c != null) out.add(c);
        }
        return out;
    }

    private JsonNode locateBarsArray(JsonNode root) {
        // v3 shape: { "result": [ { "symbol":..., "result": [ {bar}, ... ] } ] }
        if (root.has("result")) {
            JsonNode result = root.get("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode first = result.get(0);
                // per-symbol object with a nested "result" bar array
                if (first.has("result")) return first.get("result");
                // or the result array is already the bars
                if (first.has("close") || first.has("c")) return result;
            }
            return null;
        }
        // Fallbacks for alternative shapes
        if (root.isArray()) {
            if (root.size() == 0) return null;
            JsonNode first = root.get(0);
            if (first.has("result")) return first.get("result");
            if (first.has("bars")) return first.get("bars");
            if (first.has("close") || first.has("c")) return root;
            return null;
        }
        if (root.has("bars")) return root.get("bars");
        if (root.has("data")) return locateBarsArray(root.get("data"));
        return null;
    }

    private Candle mapBar(String ticker, JsonNode bar) {
        if (bar == null) return null;
        try {
            BigDecimal close = num(bar, "close", "c");
            if (close == null || close.compareTo(BigDecimal.ZERO) <= 0) return null;

            BigDecimal open = orElse(num(bar, "open", "o"), close);
            BigDecimal high = orElse(num(bar, "high", "h"), close);
            BigDecimal low  = orElse(num(bar, "low", "l"), close);
            long volume     = longOf(bar, "volume", "v");
            Instant ts      = timestampOf(bar, "timestamp", "t", "time");

            return new Candle(ticker, ts, open, high, low, close, volume);
        } catch (Exception e) {
            log.debug("[MarketDataService] Failed to map bar for ticker={}: {}", ticker, bar);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // JSON field helpers (tolerant of naming)
    // -----------------------------------------------------------------------

    private static BigDecimal num(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                try { return new BigDecimal(v.asText().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static BigDecimal orElse(BigDecimal v, BigDecimal fallback) {
        return v != null ? v : fallback;
    }

    private static long longOf(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                try { return Long.parseLong(v.asText().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 0L;
    }

    private static Instant timestampOf(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                String s = v.asText().trim();
                // 1) epoch millis (13 digits) or seconds (10 digits)
                try {
                    long epoch = Long.parseLong(s);
                    return s.length() > 12
                            ? Instant.ofEpochMilli(epoch)
                            : Instant.ofEpochSecond(epoch);
                } catch (NumberFormatException ignored) { }
                // 2) ISO-8601 with offset, e.g. 2021-12-28T09:00:09.945+0000
                try {
                    return java.time.OffsetDateTime
                            .parse(s, java.time.format.DateTimeFormatter.ofPattern(
                                    "yyyy-MM-dd'T'HH:mm:ss[.SSS]Z"))
                            .toInstant();
                } catch (Exception ignored) { }
                // 3) plain ISO instant
                try {
                    String iso = s.endsWith("Z") ? s : s + "Z";
                    return Instant.parse(iso);
                } catch (Exception ignored) { }
            }
        }
        return Instant.now();
    }
}
