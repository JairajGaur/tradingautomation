package com.trading.api;

import com.trading.config.UniverseLoader;
import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxCalculator.Advice;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.indicator.AdxService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * REST API — simple <b>BUY / SELL / HOLD</b> recommendation from the ADX/DI reading.
 * Read-only diagnostic: no orders, no state change.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/adx/recommend</td>
 *       <td>Verdict for one/some tickers, or the whole universe when none given</td></tr>
 * </table>
 *
 * <p>Symbol resolution:</p>
 * <ul>
 *   <li>{@code ?ticker=AAPL} or {@code ?tickers=AAPL,TSLA} — verdict for those symbols.</li>
 *   <li>Neither given — screens the configured universe ({@code webull.trading.universe-path}).</li>
 * </ul>
 *
 * <p>Query params: {@code timespan} (default M1), plus optional {@code period} /
 * {@code threshold} / {@code rising} overrides (else the configured {@code webull.adx.*}).
 * The verdict is computed on the latest <b>completed</b> bar (the in-progress bar is
 * dropped so signals don't flicker intra-bar).</p>
 */
@RestController
@RequestMapping("/api/adx")
public class AdxRecommendationController {

    private static final Logger log = LoggerFactory.getLogger(AdxRecommendationController.class);

    private final WebullProperties props;
    private final UniverseLoader universeLoader;
    private final BarDataManager barData;
    private final AdxService adx;

    public AdxRecommendationController(WebullProperties props,
                                       UniverseLoader universeLoader,
                                       BarDataManager barData,
                                       AdxService adx) {
        this.props = props;
        this.universeLoader = universeLoader;
        this.barData = barData;
        this.adx = adx;
    }

    @GetMapping("/recommend")
    public ResponseEntity<?> recommend(
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false) String tickers,
            @RequestParam(defaultValue = "M1") String timespan,
            @RequestParam(required = false) Integer period,
            @RequestParam(required = false) BigDecimal threshold,
            @RequestParam(name = "rising", required = false) Integer rising) {

        // Validate timespan.
        String ts;
        try {
            ts = MarketDataService.normaliseTimespan(timespan);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "timestamp", TimeFormat.nowEt()));
        }

        int effPeriod = period != null ? period : adx.period();
        BigDecimal effThreshold = threshold != null ? threshold : adx.threshold();
        int effRising = rising != null ? rising : adx.rising();
        if (effPeriod < 1 || effRising < 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "period and rising must be >= 1",
                    "timestamp", TimeFormat.nowEt()));
        }

        // Resolve symbols: explicit ticker/tickers wins, else the universe file.
        String explicit = (ticker != null && !ticker.isBlank()) ? ticker
                : (tickers != null && !tickers.isBlank()) ? tickers : null;
        boolean scannedUniverse = explicit == null;
        List<String> symbols = resolveSymbols(explicit);
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tickers to evaluate (universe empty and no ?ticker=/?tickers= provided)",
                    "timestamp", TimeFormat.nowEt()));
        }

        log.info("[AdxRecommend] Evaluating {} symbol(s) on {} (period={} threshold={} rising={})",
                symbols.size(), ts, effPeriod, effThreshold, effRising);

        // Prime the shared bar cache in one batched request when scanning many.
        int warmup = props.trading().warmupBars();
        if (symbols.size() > 1) {
            try {
                barData.getBarsBatch(symbols, ts, warmup);
            } catch (Exception e) {
                log.warn("[AdxRecommend] batch prefetch failed for {}: {}", ts, e.getMessage());
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int buy = 0, sell = 0, hold = 0;
        for (String symbol : symbols) {
            Map<String, Object> row = evaluate(symbol, ts, effPeriod, effThreshold, effRising, warmup);
            results.add(row);
            switch (String.valueOf(row.get("action"))) {
                case "BUY" -> buy++;
                case "SELL" -> sell++;
                default -> hold++;
            }
        }

        // Single explicit ticker → return just that one object (simplest to read).
        if (explicit != null && symbols.size() == 1) {
            return ResponseEntity.ok(results.get(0));
        }

        // Sort so actionable names float to the top: BUY, then SELL, then HOLD.
        results.sort((a, b) -> rank(String.valueOf(a.get("action")))
                - rank(String.valueOf(b.get("action"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", scannedUniverse ? "universe" : "requested");
        body.put("timespan", ts);
        body.put("evaluated", results.size());
        body.put("counts", Map.of("buy", buy, "sell", sell, "hold", hold));
        body.put("recommendations", results);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Builds the per-symbol verdict on the latest COMPLETED bar. */
    private Map<String, Object> evaluate(String symbol, String ts, int period,
                                         BigDecimal threshold, int rising, int warmup) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticker", symbol);
        try {
            List<Candle> bars = barData.getBars(symbol, ts, warmup);
            // Need at least 2*period completed bars (+1 for the dropped in-progress bar).
            if (bars == null || bars.size() < 2 * period + 1) {
                row.put("action", "HOLD");
                row.put("bias", "NEUTRAL");
                row.put("reason", "Not enough data to evaluate ADX.");
                return row;
            }
            // Drop the last (possibly in-progress) bar, then compute the series.
            List<Candle> completed = bars.subList(0, bars.size() - 1);
            List<Point> points = AdxCalculator.calculate(completed, period, threshold, rising);

            // Latest point with an ADX value + the ADX `rising` bars earlier (for the reason).
            Point latest = null;
            int latestIdx = -1;
            for (int i = points.size() - 1; i >= 0; i--) {
                if (points.get(i).adx() != null) { latest = points.get(i); latestIdx = i; break; }
            }
            BigDecimal prevAdx = null;
            if (latest != null) {
                int backIdx = latestIdx - rising;
                if (backIdx >= 0 && points.get(backIdx).adx() != null) {
                    prevAdx = points.get(backIdx).adx();
                }
            }

            Advice advice = AdxCalculator.recommend(latest, prevAdx);
            row.put("action", advice.action().name());
            row.put("bias", biasOf(latest));   // BULLISH / BEARISH / NEUTRAL
            row.put("reason", advice.reason());
            row.put("close", completed.get(completed.size() - 1).close().toPlainString());
        } catch (Exception e) {
            log.warn("[AdxRecommend] eval failed for {} {}: {}", symbol, ts, e.getMessage());
            row.put("action", "HOLD");
            row.put("bias", "NEUTRAL");
            row.put("reason", "Could not evaluate (data unavailable).");
        }
        return row;
    }

    /**
     * Directional bias from the DMI reading: +DI over −DI → BULLISH, −DI over +DI →
     * BEARISH, else (equal / warm-up / no data) → NEUTRAL. Independent of trend
     * strength — this is direction only, not "how strong".
     */
    private static String biasOf(AdxCalculator.Point p) {
        if (p == null || p.direction() == null) return "NEUTRAL";
        return switch (p.direction()) {
            case UP -> "BULLISH";
            case DOWN -> "BEARISH";
            default -> "NEUTRAL";   // FLAT
        };
    }

    /** Ordering for the response: BUY first, then SELL, then HOLD. */
    private static int rank(String action) {
        return switch (action) {
            case "BUY" -> 0;
            case "SELL" -> 1;
            default -> 2;
        };
    }

    private List<String> resolveSymbols(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String t : Arrays.asList(explicit.split(","))) {
                String s = t.trim().toUpperCase();
                if (!s.isEmpty()) set.add(s);
            }
            return new ArrayList<>(set);
        }
        return new ArrayList<>(universeLoader.getUniverse());
    }
}
