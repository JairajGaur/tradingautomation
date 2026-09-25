package com.trading.api;

import com.trading.config.UniverseLoader;
import com.trading.config.WebullProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * REST API — screens a universe of tickers by Supertrend direction across one or
 * more timeframes. Read-only diagnostic: no orders, no state change.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/filter/supertrend</td>
 *       <td>List tickers whose Supertrend matches the requested direction on ALL
 *           requested timeframes</td></tr>
 * </table>
 *
 * <p>Query params:</p>
 * <ul>
 *   <li>{@code timeframes} — comma-separated ST timeframes (e.g. {@code M5,M15,M30}).
 *       A ticker PASSES only if its Supertrend matches {@code direction} on EVERY one
 *       (AND). Default {@code M15}.</li>
 *   <li>{@code direction} — {@code UP} or {@code DOWN} (default {@code UP}).</li>
 *   <li>{@code tickers} — optional comma-separated symbols to screen instead of the
 *       universe file (e.g. {@code TQQQ,SQQQ}).</li>
 * </ul>
 *
 * <p>Uses the app's Supertrend params ({@code webull.supertrend}) on the latest
 * COMPLETED bar of each timeframe.</p>
 */
@RestController
@RequestMapping("/api/filter")
public class SupertrendFilterController {

    private static final Logger log = LoggerFactory.getLogger(SupertrendFilterController.class);

    private final WebullProperties props;
    private final UniverseLoader universeLoader;
    private final BarDataManager barData;
    private final SupertrendService supertrend;

    public SupertrendFilterController(WebullProperties props,
                                      UniverseLoader universeLoader,
                                      BarDataManager barData,
                                      SupertrendService supertrend) {
        this.props = props;
        this.universeLoader = universeLoader;
        this.barData = barData;
        this.supertrend = supertrend;
    }

    @GetMapping("/supertrend")
    public ResponseEntity<Map<String, Object>> filter(
            @RequestParam(defaultValue = "M15") String timeframes,
            @RequestParam(defaultValue = "UP") String direction,
            @RequestParam(required = false) String tickers) {

        // Requested direction.
        Direction want;
        try {
            want = Direction.valueOf(direction.trim().toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid direction '" + direction + "' (expected UP or DOWN)",
                    "timestamp", TimeFormat.nowEt()));
        }

        // Parse + validate timeframes.
        List<String> tfs = new ArrayList<>();
        try {
            for (String raw : timeframes.split(",")) {
                String t = raw.trim();
                if (!t.isEmpty()) tfs.add(MarketDataService.normaliseTimespan(t));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "timestamp", TimeFormat.nowEt()));
        }
        if (tfs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No timeframes provided",
                    "timestamp", TimeFormat.nowEt()));
        }

        // Resolve symbols: explicit ?tickers= wins, else the universe file.
        List<String> symbols = resolveSymbols(tickers);
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tickers to screen (universe empty and no ?tickers= provided)",
                    "timestamp", TimeFormat.nowEt()));
        }

        log.info("[SupertrendFilter] Screening {} ticker(s) for ST={} on {}",
                symbols.size(), want, tfs);

        // Prime the shared bar cache per timeframe (one batched request each).
        int warmup = props.trading().warmupBars();
        for (String tf : tfs) {
            try {
                barData.getBarsBatch(symbols, tf, warmup);
            } catch (Exception e) {
                log.warn("[SupertrendFilter] prefetch failed for {}: {}", tf, e.getMessage());
            }
        }

        List<String> passed = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Map<String, Object> perTf = new LinkedHashMap<>();
            boolean all = true;
            for (String tf : tfs) {
                Direction dir = directionFor(symbol, tf);
                perTf.put(tf, dir == null ? "UNAVAILABLE" : dir.name());
                if (dir != want) all = false;
            }
            if (all) passed.add(symbol);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", symbol);
            row.put("passed", all);
            row.put("supertrend", perTf);
            results.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("direction", want.name());
        body.put("timeframes", tfs);
        body.put("scanned", symbols.size());
        body.put("passedCount", passed.size());
        body.put("passed", passed);
        body.put("results", results);
        body.put("supertrendParams", Map.of(
                "length", supertrend.length(),
                "factor", supertrend.factor().toPlainString()));
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Latest COMPLETED-bar Supertrend direction for one timeframe, or null if unavailable. */
    private Direction directionFor(String ticker, String timeframe) {
        try {
            int count = Math.max(supertrend.length() * 10, 120);
            List<Candle> bars = barData.getBars(ticker, timeframe, count);
            return supertrend.latestCompletedDirection(bars);
        } catch (Exception e) {
            log.warn("[SupertrendFilter] ST failed for {} {}: {}", ticker, timeframe, e.getMessage());
            return null;
        }
    }

    private List<String> resolveSymbols(String tickers) {
        if (tickers != null && !tickers.isBlank()) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String t : Arrays.asList(tickers.split(","))) {
                String s = t.trim().toUpperCase();
                if (!s.isEmpty()) set.add(s);
            }
            return new ArrayList<>(set);
        }
        return new ArrayList<>(universeLoader.getUniverse());
    }
}
