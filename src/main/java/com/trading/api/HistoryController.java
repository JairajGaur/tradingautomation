package com.trading.api;

import com.trading.config.WebullProperties;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — Historical bar data.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/history/{ticker}</td>
 *       <td>All fetched 1-minute historical bars (OHLCV) for a stock</td></tr>
 * </table>
 *
 * <p>Unlike the EMA endpoint this does not require the ticker to be in the
 * watchlist — you can query any US stock symbol.</p>
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final WebullProperties props;
    private final MarketDataService marketDataService;

    public HistoryController(WebullProperties props, MarketDataService marketDataService) {
        this.props = props;
        this.marketDataService = marketDataService;
    }

    /**
     * Returns the full list of 1-minute historical bars for {@code ticker}.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code count} — number of bars to fetch (default = {@code webull.trading.warmup-bars},
     *       max 1650 for M1 per Webull).</li>
     * </ul>
     *
     * <p>Example — {@code GET /api/history/AAPL?count=200}:
     * <pre>{@code
     * {
     *   "ticker": "AAPL",
     *   "count": 200,
     *   "bars": [
     *     { "timestamp": "2024-01-15T14:30:00Z", "open": "185.10",
     *       "high": "185.40", "low": "184.90", "close": "185.20", "volume": 12345 },
     *     ...
     *   ],
     *   "timestamp": "2024-01-15T14:31:00Z"
     * }
     * }</pre>
     *
     * <p>On a Webull error, returns 502 with a {@code webullError} block.</p>
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable String ticker,
            @RequestParam(required = false) Integer count,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) String sessions) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        int barCount = count != null && count > 0 ? count : props.trading().warmupBars();

        log.info("[HistoryController] GET /api/history/{} count={} timespan={} sessions={}",
                symbol, barCount, timespan, sessions);

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

        List<Map<String, Object>> bars = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("timestamp", c.timestamp().toString());          // UTC (ISO-8601)
            bar.put("timestampEt", TimeFormat.toEt(c.timestamp()));  // America/New_York
            bar.put("open", c.open().toPlainString());
            bar.put("high", c.high().toPlainString());
            bar.put("low", c.low().toPlainString());
            bar.put("close", c.close().toPlainString());
            bar.put("volume", c.volume());
            bars.add(bar);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("timespan", ts);
        body.put("sessions", sess != null ? sess : props.trading().tradingSessions());
        body.put("count", bars.size());
        body.put("bars", bars);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
