package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.indicator.SupertrendCalculator;
import com.trading.indicator.SupertrendCalculator.Point;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — Supertrend indicator values (for verification against the Webull chart).
 *
 * <p>This endpoint exists to <b>inspect and validate</b> the Supertrend calculation
 * across timeframes before any trading logic is wired to it. It does not place
 * orders.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/supertrend/{ticker}</td>
 *       <td>Supertrend line, ATR, direction and flip signal per bar</td></tr>
 * </table>
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code length}   — ATR period (default 7)</li>
 *   <li>{@code factor}   — ATR multiplier (default 3)</li>
 *   <li>{@code timespan} — M1/M5/M15/M30/M60/M120/M240/D/W/M/Y (default M1)</li>
 *   <li>{@code sessions} — RTH/PRE/ATH/OVN (default: configured trading sessions)</li>
 *   <li>{@code count}    — bars to fetch (default = warmup-bars)</li>
 *   <li>{@code bars}     — how many most-recent points to return (default 50; use 0 for all)</li>
 * </ul>
 *
 * <p>Any US stock symbol works — the ticker need not be in the watchlist.</p>
 */
@RestController
@RequestMapping("/api/supertrend")
public class SupertrendController {

    private static final Logger log = LoggerFactory.getLogger(SupertrendController.class);

    private static final int DEFAULT_LENGTH = 7;
    private static final BigDecimal DEFAULT_FACTOR = BigDecimal.valueOf(3);
    private static final int DEFAULT_RETURN_BARS = 50;

    private final WebullProperties props;
    private final MarketDataService marketDataService;

    public SupertrendController(WebullProperties props, MarketDataService marketDataService) {
        this.props = props;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getSupertrend(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "7") int length,
            @RequestParam(defaultValue = "3") BigDecimal factor,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count,
            @RequestParam(name = "bars", defaultValue = "50") int returnBars) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        int barCount = count != null && count > 0 ? count : props.trading().warmupBars();

        log.info("[SupertrendController] GET /api/supertrend/{} length={} factor={} timespan={} sessions={} count={}",
                symbol, length, factor, timespan, sessions, barCount);

        // Validate indicator params.
        if (length < 1) {
            return badRequest("length must be >= 1");
        }
        if (factor == null || factor.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("factor must be > 0");
        }

        // Validate timespan / sessions.
        String ts;
        String sess;
        try {
            ts = MarketDataService.normaliseTimespan(timespan);
            sess = MarketDataService.normaliseSessions(sessions);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS,
                    "timestamp", TimeFormat.nowEt()));
        }

        // Fetch bars.
        List<Candle> candles;
        try {
            candles = marketDataService.fetchHistoricalBars(symbol, barCount, ts, sess);
        } catch (MarketDataException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("mode", props.trading().mode().name());
            body.put("endpoint", props.resolvedApiHost());
            body.put("webullError", e.toDetailMap());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }

        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No market data available for ticker",
                    "ticker", symbol,
                    "timespan", ts,
                    "timestamp", TimeFormat.nowEt()));
        }
        if (candles.size() < length) {
            return badRequest("Not enough bars (" + candles.size() + ") for length=" + length);
        }

        // Compute Supertrend.
        List<Point> points = SupertrendCalculator.calculate(candles, length, factor);

        // Trim to the most-recent N (returnBars == 0 → all).
        int from = (returnBars > 0 && returnBars < points.size())
                ? points.size() - returnBars : 0;

        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = from; i < points.size(); i++) {
            Point p = points.get(i);
            Candle c = p.candle();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", c.timestamp().toString());          // UTC (ISO-8601)
            row.put("timestampEt", TimeFormat.toEt(c.timestamp()));  // America/New_York
            row.put("open", c.open().toPlainString());
            row.put("high", c.high().toPlainString());
            row.put("low", c.low().toPlainString());
            row.put("close", c.close().toPlainString());
            row.put("atr", p.atr() == null ? null : p.atr().toPlainString());
            row.put("supertrend", p.supertrend() == null ? null : p.supertrend().toPlainString());
            row.put("direction", p.direction() == null ? null : p.direction().name());
            row.put("flip", p.flip());
            series.add(row);
        }

        Point latest = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).supertrend() != null) { latest = points.get(i); break; }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("length", length);
        body.put("factor", factor.toPlainString());
        body.put("timespan", ts);
        body.put("sessions", sess != null ? sess : props.trading().tradingSessions());
        body.put("barsFetched", candles.size());
        if (latest != null) {
            body.put("latestSupertrend", latest.supertrend().toPlainString());
            body.put("latestDirection", latest.direction().name());
            body.put("latestClose", latest.candle().close().toPlainString());
        }
        body.put("returned", series.size());
        body.put("series", series);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", message,
                "timestamp", TimeFormat.nowEt()));
    }
}
