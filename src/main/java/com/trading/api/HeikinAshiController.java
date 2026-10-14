package com.trading.api;

import com.trading.config.AppProperties;
import com.trading.indicator.HeikinAshiCalculator;
import com.trading.model.Candle;
import com.trading.service.MarketDataException;
import com.trading.service.MarketDataService;
import com.trading.webull.WebullApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — <b>Heikin-Ashi</b> candle values (for inspection / verification against the chart).
 *
 * <p>Converts the symbol's regular OHLC bars into Heikin-Ashi candles via
 * {@link HeikinAshiCalculator}. Detection/analysis only — it does not place orders.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/heikin-ashi/{ticker}</td>
 *       <td>Heikin-Ashi OHLC (+ the raw OHLC and candle colour) per bar</td></tr>
 * </table>
 *
 * <h2>Timeframes</h2>
 * Supports EVERY timeframe the market-data layer serves: native broker minutes
 * (M1/M5/M15/M30/M60/M120/M240) and the SYNTHETIC multiples (M2/M3), which are resampled
 * from M1 transparently by {@link MarketDataService} — the HA transform is timeframe-agnostic.
 *
 * <h2>Query params</h2>
 * <ul>
 *   <li>{@code timespan} — M1/M2/M3/M5/M15/M30/M60/M120/M240/D/W/M/Y (default M1)</li>
 *   <li>{@code sessions} — RTH/PRE/ATH/OVN (default: configured trading sessions)</li>
 *   <li>{@code count}    — bars to fetch (default = warmup-bars)</li>
 *   <li>{@code bars}     — how many most-recent HA points to return (default 50; 0 = all)</li>
 * </ul>
 *
 * <p>Any US stock symbol works — the ticker need not be in the watchlist.</p>
 */
@RestController
@RequestMapping("/api/heikin-ashi")
public class HeikinAshiController {

    private static final Logger log = LoggerFactory.getLogger(HeikinAshiController.class);

    private final AppProperties props;
    private final WebullApiProperties brokerApi;
    private final MarketDataService marketDataService;

    public HeikinAshiController(AppProperties props, WebullApiProperties brokerApi,
                                MarketDataService marketDataService) {
        this.props = props;
        this.brokerApi = brokerApi;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getHeikinAshi(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count,
            @RequestParam(name = "bars", defaultValue = "50") int returnBars) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        int barCount = count != null && count > 0 ? count : props.engine().warmupBars();

        log.info("[HeikinAshiController] GET /api/heikin-ashi/{} timespan={} sessions={} count={}",
                symbol, timespan, sessions, barCount);

        // Validate timespan / sessions (same normalisers the other inspection endpoints use —
        // this is what makes every native + synthetic timeframe supported here).
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

        // Fetch bars (oldest-first). Synthetic M2/M3 are resampled from M1 inside this call.
        List<Candle> candles;
        try {
            candles = marketDataService.fetchHistoricalBars(symbol, barCount, ts, sess);
        } catch (MarketDataException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("mode", props.engine().mode().name());
            body.put("endpoint", brokerApi.resolvedApiHost());
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

        // Transform to Heikin-Ashi (computed over the FULL fetched series so the running
        // average is seeded continuously; we only trim the OUTPUT below).
        List<Candle> ha = HeikinAshiCalculator.calculate(candles);

        // Trim to the most-recent N (returnBars == 0 → all). We trim BOTH lists together so the
        // raw bar lines up with its HA bar index-for-index.
        int from = (returnBars > 0 && returnBars < ha.size()) ? ha.size() - returnBars : 0;

        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = from; i < ha.size(); i++) {
            Candle h = ha.get(i);
            Candle raw = candles.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", h.timestamp().toString());          // UTC (ISO-8601)
            row.put("timestampEt", TimeFormat.toEt(h.timestamp()));  // America/New_York
            // Heikin-Ashi OHLC.
            row.put("haOpen", h.open().toPlainString());
            row.put("haHigh", h.high().toPlainString());
            row.put("haLow", h.low().toPlainString());
            row.put("haClose", h.close().toPlainString());
            // GREEN when HA close > HA open, RED when below, DOJI when equal.
            int cmp = h.close().compareTo(h.open());
            row.put("color", cmp > 0 ? "GREEN" : (cmp < 0 ? "RED" : "DOJI"));
            // Raw OHLC alongside, for direct comparison against the chart / other endpoints.
            row.put("open", raw.open().toPlainString());
            row.put("high", raw.high().toPlainString());
            row.put("low", raw.low().toPlainString());
            row.put("close", raw.close().toPlainString());
            row.put("volume", raw.volume());
            series.add(row);
        }

        Candle latest = ha.get(ha.size() - 1);
        int latestCmp = latest.close().compareTo(latest.open());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("timespan", ts);
        body.put("sessions", sess != null ? sess : props.engine().tradingSessions());
        body.put("barsFetched", candles.size());
        body.put("latestHaOpen", latest.open().toPlainString());
        body.put("latestHaClose", latest.close().toPlainString());
        body.put("latestColor", latestCmp > 0 ? "GREEN" : (latestCmp < 0 ? "RED" : "DOJI"));
        body.put("returned", series.size());
        body.put("series", series);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
