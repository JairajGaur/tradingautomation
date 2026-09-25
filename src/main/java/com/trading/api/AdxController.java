package com.trading.api;

import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.indicator.AdxService;
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
 * REST API — Wilder Directional Movement values: <b>+DI</b>, <b>−DI</b> (direction),
 * <b>ADX</b> (strength), the <b>DI crossover</b> signal, and two flagged trend patterns
 * ("low-ADX-rising" and "high-ADX reversal with DI cross").
 *
 * <p>This endpoint exists to <b>inspect and validate</b> the ADX/DI calculation across
 * timeframes. It does not place orders.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/adx/{ticker}</td>
 *       <td>+DI, −DI, ADX, DI-crossover and trend-pattern flags per bar</td></tr>
 * </table>
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code period}    — DI/ADX lookback (default 14)</li>
 *   <li>{@code threshold} — ADX level that separates weak/ranging from trending (default 25)</li>
 *   <li>{@code rising}    — bars back ADX is compared against to judge "rising" (default 3)</li>
 *   <li>{@code timespan}  — M1/M5/M15/M30/M60/M120/M240/D/W/M/Y (default M1)</li>
 *   <li>{@code sessions}  — RTH/PRE/ATH/OVN (default: configured trading sessions)</li>
 *   <li>{@code count}     — bars to fetch (default = warmup-bars)</li>
 *   <li>{@code bars}      — how many most-recent points to return (default 50; use 0 for all)</li>
 * </ul>
 *
 * <p>Any US stock symbol works — the ticker need not be in the watchlist.</p>
 */
@RestController
@RequestMapping("/api/adx")
public class AdxController {

    private static final Logger log = LoggerFactory.getLogger(AdxController.class);

    private final WebullProperties props;
    private final MarketDataService marketDataService;
    private final AdxService adx;

    public AdxController(WebullProperties props, MarketDataService marketDataService, AdxService adx) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.adx = adx;
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getAdx(
            @PathVariable String ticker,
            @RequestParam(required = false) Integer period,
            @RequestParam(required = false) BigDecimal threshold,
            @RequestParam(name = "rising", required = false) Integer rising,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count,
            @RequestParam(name = "bars", defaultValue = "0") int returnBars) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        int barCount = count != null && count > 0 ? count : props.trading().warmupBars();
        // Query params override the configured webull.adx.* defaults per request.
        int effPeriod = period != null ? period : adx.period();
        BigDecimal trendThreshold = threshold != null ? threshold : adx.threshold();
        int risingLookback = rising != null ? rising : adx.rising();

        log.info("[AdxController] GET /api/adx/{} period={} threshold={} rising={} timespan={} sessions={} count={}",
                symbol, effPeriod, trendThreshold, risingLookback, timespan, sessions, barCount);

        if (effPeriod < 1) {
            return badRequest("period must be >= 1");
        }
        if (risingLookback < 1) {
            return badRequest("rising must be >= 1");
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
        // ADX needs ~2*period bars before the first value appears.
        int minBars = 2 * effPeriod;
        if (candles.size() < minBars) {
            return badRequest("Not enough bars (" + candles.size() + ") for period=" + effPeriod
                    + " (need at least " + minBars + ")");
        }

        // Compute the DI/ADX series.
        List<Point> points = AdxCalculator.calculate(candles, effPeriod, trendThreshold, risingLookback);

        // Trim to the most-recent N (returnBars == 0 → all).
        // Per-bar series is OPT-IN: only built when the caller passes ?bars=N (N>0).
        // By default the response is just the summary + latest values.
        List<Map<String, Object>> series = null;
        if (returnBars > 0) {
            int from = returnBars < points.size() ? points.size() - returnBars : 0;
            series = new ArrayList<>();
            for (int i = from; i < points.size(); i++) {
                Point p = points.get(i);
                Candle c = p.candle();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", c.timestamp().toString());          // UTC (ISO-8601)
                row.put("timestampEt", TimeFormat.toEt(c.timestamp()));  // America/New_York
                row.put("close", c.close().toPlainString());
                row.put("plusDi", p.plusDi() == null ? null : p.plusDi().toPlainString());
                row.put("minusDi", p.minusDi() == null ? null : p.minusDi().toPlainString());
                row.put("adx", p.adx() == null ? null : p.adx().toPlainString());
                row.put("diCross", p.diCross() == null ? null : p.diCross().name());
                row.put("direction", p.direction() == null ? null : p.direction().name());
                row.put("pattern", p.pattern() == null ? null : p.pattern().name());
                AdxCalculator.Strength rowStrength = AdxCalculator.strengthOf(p.adx());
                row.put("strength", rowStrength == null ? null : rowStrength.name());
                row.put("signal", AdxCalculator.signalOf(p));   // e.g. STRONG_UP / UP / CHOP / REVERSAL_UP
                series.add(row);
            }
        }

        // Latest computed point (has ADX) for the summary — track its index so we
        // can look back `risingLookback` bars to judge whether ADX is rising/falling.
        Point latest = null;
        int latestIdx = -1;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).adx() != null) { latest = points.get(i); latestIdx = i; break; }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("period", effPeriod);
        body.put("threshold", trendThreshold.toPlainString());
        body.put("risingLookback", risingLookback);
        body.put("timespan", ts);
        body.put("sessions", sess != null ? sess : props.trading().tradingSessions());
        body.put("barsFetched", candles.size());
        if (latest != null) {
            // ADX a few bars back, for the "gaining / losing strength" read.
            BigDecimal prevAdx = null;
            int backIdx = latestIdx - risingLookback;
            if (backIdx >= 0 && points.get(backIdx).adx() != null) {
                prevAdx = points.get(backIdx).adx();
            }
            AdxCalculator.Strength strength = AdxCalculator.strengthOf(latest.adx());
            String trendSlope = prevAdx == null ? "UNKNOWN"
                    : (latest.adx().compareTo(prevAdx) > 0 ? "RISING"
                     : latest.adx().compareTo(prevAdx) < 0 ? "FALLING" : "FLAT");

            // Human-readable summary block — read this first.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("text", AdxCalculator.describe(latest, prevAdx));
            summary.put("direction", latest.direction().name());        // UP / DOWN / FLAT
            summary.put("strength", strength == null ? null : strength.name());
            summary.put("trendSlope", trendSlope);                      // is ADX rising or falling?
            summary.put("signal", AdxCalculator.signalOf(latest));      // STRONG_UP / UP / CHOP / ...
            summary.put("pattern", latest.pattern() == null ? null : latest.pattern().name());
            summary.put("diCross", latest.diCross() == null ? null : latest.diCross().name());
            body.put("summary", summary);

            body.put("latestPlusDi", latest.plusDi().toPlainString());
            body.put("latestMinusDi", latest.minusDi().toPlainString());
            body.put("latestAdx", latest.adx().toPlainString());
            body.put("latestDirection", latest.direction().name());
            body.put("latestDiCross", latest.diCross() == null ? null : latest.diCross().name());
            body.put("latestPattern", latest.pattern() == null ? null : latest.pattern().name());
            body.put("latestClose", latest.candle().close().toPlainString());
        }
        if (series != null) {
            body.put("returned", series.size());
            body.put("series", series);
        }
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", message,
                "timestamp", TimeFormat.nowEt()));
    }
}
