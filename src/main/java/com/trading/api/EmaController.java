package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;
import com.trading.service.MarketDataException;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API — EMA indicator values for any US ticker (manual inspection).
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/ema/{ticker}</td>
 *       <td>Configured EMA values (20/100/200/600 by default) for any US symbol</td></tr>
 * </table>
 *
 * <p>The list of EMA periods is configurable via {@code webull.trading.ema-periods}
 * in {@code application.yml}.</p>
 */
@RestController
@RequestMapping("/api/ema")
public class EmaController {

    private static final Logger log = LoggerFactory.getLogger(EmaController.class);

    /** Webull's per-request bar cap (M1 clamps around here); fetch no more than this. */
    private static final int EMA_MAX_BARS = 1200;

    private final WebullProperties props;
    private final MarketDataService marketDataService;

    public EmaController(WebullProperties props,
                          MarketDataService marketDataService) {
        this.props = props;
        this.marketDataService = marketDataService;
    }

    // -----------------------------------------------------------------------
    // GET /api/ema/{ticker}
    // -----------------------------------------------------------------------

    /**
     * Returns the configured EMA values for a ticker.
     *
     * <p>Any US symbol works — no watchlist restriction. Fresh historical bars
     * are fetched and each configured EMA period is computed with
     * {@link EmaCalculator} using {@link BigDecimal} precision.</p>
     *
     * <p>Example — {@code GET /api/ema/AAPL}:
     * <pre>{@code
     * {
     *   "ticker": "AAPL",
     *   "barsUsed": 700,
     *   "lastClose": "185.20",
     *   "emaValues": {
     *     "20":  "184.95000000",
     *     "100": "183.10000000",
     *     "200": "181.72000000",
     *     "600": "178.40000000"
     *   },
     *   "periods": [20, 100, 200, 600],
     *   "timestamp": "2024-01-15T14:30:00Z"
     * }
     * }</pre>
     *
     * <p>If a configured period needs more bars than were returned, that period's
     * value is reported as {@code "INSUFFICIENT_DATA"} rather than failing the whole request.</p>
     *
     * @param ticker the equity symbol (case-insensitive)
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getEma(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) String sessions) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        log.info("[EmaController] GET /api/ema/{} timespan={} sessions={}", symbol, timespan, sessions);

        // ── 0. Validate timespan + sessions ───────────────────────────────
        String ts;
        String sess;
        try {
            ts = MarketDataService.normaliseTimespan(timespan);
            sess = MarketDataService.normaliseSessions(sessions);  // null when not provided
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS,
                    "timestamp", TimeFormat.nowEt()));
        }

        // Manual inspection endpoint — any US symbol works (no watchlist restriction).
        // Fetch enough history for the LARGEST EMA period to converge to the value a
        // charting platform shows. An EMA needs ~3x its period of warm-up; with too
        // few bars the long EMAs (e.g. 600) don't converge. Capped at Webull's M1
        // limit (~1200 bars/request).
        int maxPeriod = props.trading().emaPeriods().stream()
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(600);
        int barsToFetch = Math.min(EMA_MAX_BARS, Math.max(props.trading().warmupBars(), maxPeriod * 3));
        List<Candle> history;
        try {
            history = marketDataService.fetchHistoricalBars(symbol, barsToFetch, ts, sess);
        } catch (MarketDataException e) {
            // Surface the REAL Webull error (http status, error code, message, request id)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("mode", props.trading().mode().name());
            body.put("endpoint", props.resolvedApiHost());
            body.put("webullError", e.toDetailMap());   // ← actual Webull details
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }

        if (history.isEmpty()) {
            // Distinct case: the call succeeded but returned zero bars (not an error).
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No market data available for ticker",
                    "ticker", symbol,
                    "mode", props.trading().mode().name(),
                    "endpoint", props.resolvedApiHost(),
                    "hint", "The market-data API returned an empty bar list (no error). "
                          + "In PAPER mode the sandbox data can be sparse or unavailable "
                          + "outside market hours.",
                    "timestamp", TimeFormat.nowEt()));
        }

        List<BigDecimal> closes = history.stream()
                .map(Candle::close)
                .collect(Collectors.toList());

        // ── 3. Compute each configured EMA period ─────────────────────────
        List<Integer> periods = props.trading().emaPeriods();
        Map<String, String> emaValues = new LinkedHashMap<>();

        for (Integer period : periods) {
            if (period == null || period < 1) continue;
            if (closes.size() < period) {
                emaValues.put(String.valueOf(period), "INSUFFICIENT_DATA");
                log.debug("[EmaController] ticker={} period={} needs {} bars but only {} available",
                        symbol, period, period, closes.size());
                continue;
            }
            try {
                BigDecimal ema = EmaCalculator.calculate(closes, period);
                emaValues.put(String.valueOf(period),
                        ema.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            } catch (Exception e) {
                emaValues.put(String.valueOf(period), "ERROR");
                log.error("[EmaController] Failed computing EMA({}) for ticker={}", period, symbol, e);
            }
        }

        // ── 4. Build response ─────────────────────────────────────────────
        BigDecimal lastClose = closes.get(closes.size() - 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("timespan", ts);
        body.put("sessions", sess != null ? sess : props.trading().tradingSessions());
        body.put("barsUsed", closes.size());
        body.put("lastClose", lastClose.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        body.put("emaValues", emaValues);
        body.put("periods", periods);
        body.put("timestamp", TimeFormat.nowEt());

        return ResponseEntity.ok(body);
    }
}
