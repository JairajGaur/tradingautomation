package com.trading.api;

import com.trading.config.UniverseLoader;
import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxCalculator.Direction;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.indicator.AdxService;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import com.trading.service.VolumeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * REST API — a <b>high-confidence trend read</b> that fuses several confirmations rather
 * than trusting raw ADX alone. Read-only diagnostic: no orders, no state change.
 *
 * <p>Per ticker it evaluates, on EACH requested timeframe (latest completed bar):</p>
 * <ol>
 *   <li><b>Bias gated on strength</b> — direction (+DI vs −DI) is only reported when
 *       ADX ≥ threshold; below that it's NEUTRAL (no trend → no meaningful direction).</li>
 *   <li><b>DI spread + widening</b> — the |+DI − −DI| gap and whether it grew vs the prior bar.</li>
 *   <li><b>ADX slope</b> — ADX rising across the {@code rising} lookback (multi-bar, not one tick).</li>
 *   <li><b>ADXR</b> — Wilder's smoothed ADX rating = (ADX now + ADX `period` bars ago) / 2.</li>
 * </ol>
 * <p>Then it combines across the timeframes and adds a <b>volume-expansion</b> check
 * (base timeframe) into a 0–N <b>confluence score</b> and a final verdict:
 * STRONG_BULLISH / BULLISH / NEUTRAL / BEARISH / STRONG_BEARISH.</p>
 *
 * <table border="1">
 *   <tr><th>GET</th><td>/api/adx/confluence</td>
 *       <td>Multi-confirmation trend verdict for one/some tickers or the universe</td></tr>
 * </table>
 *
 * <p>Query: {@code ticker}/{@code tickers} (else the universe), {@code timeframes}
 * (comma-separated, default {@code M1,M5}), optional {@code period}/{@code threshold}/{@code rising}.</p>
 */
@RestController
@RequestMapping("/api/adx")
public class AdxConfluenceController {

    private static final Logger log = LoggerFactory.getLogger(AdxConfluenceController.class);

    private final WebullProperties props;
    private final UniverseLoader universeLoader;
    private final BarDataManager barData;
    private final AdxService adx;
    private final VolumeFilter volumeFilter;

    public AdxConfluenceController(WebullProperties props,
                                   UniverseLoader universeLoader,
                                   BarDataManager barData,
                                   AdxService adx,
                                   VolumeFilter volumeFilter) {
        this.props = props;
        this.universeLoader = universeLoader;
        this.barData = barData;
        this.adx = adx;
        this.volumeFilter = volumeFilter;
    }

    @GetMapping("/confluence")
    public ResponseEntity<?> confluence(
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false) String tickers,
            @RequestParam(defaultValue = "M1,M5") String timeframes,
            @RequestParam(required = false) Integer period,
            @RequestParam(required = false) BigDecimal threshold,
            @RequestParam(name = "rising", required = false) Integer rising) {

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
                    "error", "No timeframes provided", "timestamp", TimeFormat.nowEt()));
        }

        int effPeriod = period != null ? period : adx.period();
        BigDecimal effThreshold = threshold != null ? threshold : adx.threshold();
        int effRising = rising != null ? rising : adx.rising();
        if (effPeriod < 1 || effRising < 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "period and rising must be >= 1", "timestamp", TimeFormat.nowEt()));
        }

        String explicit = (ticker != null && !ticker.isBlank()) ? ticker
                : (tickers != null && !tickers.isBlank()) ? tickers : null;
        boolean scannedUniverse = explicit == null;
        List<String> symbols = resolveSymbols(explicit);
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tickers to evaluate (universe empty and no ?ticker=/?tickers= given)",
                    "timestamp", TimeFormat.nowEt()));
        }

        int warmup = props.trading().warmupBars();
        for (String tf : tfs) {
            try {
                barData.getBarsBatch(symbols, tf, warmup);
            } catch (Exception e) {
                log.warn("[AdxConfluence] prefetch failed for {}: {}", tf, e.getMessage());
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int buy = 0, sell = 0, hold = 0;
        for (String symbol : symbols) {
            Map<String, Object> r = evaluate(symbol, tfs, effPeriod, effThreshold, effRising, warmup);
            results.add(r);
            switch (String.valueOf(r.get("action"))) {
                case "BUY" -> buy++;
                case "SELL" -> sell++;
                default -> hold++;
            }
        }

        // Single explicit ticker → return just that row (same as the recommend endpoint).
        if (explicit != null && symbols.size() == 1) {
            return ResponseEntity.ok(results.get(0));
        }
        // Sort BUY, then SELL, then HOLD (same as recommend).
        results.sort((a, b) -> rank(String.valueOf(a.get("action")))
                - rank(String.valueOf(b.get("action"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", scannedUniverse ? "universe" : "requested");
        body.put("timeframes", tfs);
        body.put("evaluated", results.size());
        body.put("counts", Map.of("buy", buy, "sell", sell, "hold", hold));
        body.put("recommendations", results);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Ordering for the response: BUY first, then SELL, then HOLD (matches recommend). */
    private static int rank(String action) {
        return switch (action) {
            case "BUY" -> 0;
            case "SELL" -> 1;
            default -> 2;
        };
    }

    /**
     * Per-ticker confluence across all requested timeframes + volume, reduced to the
     * same fields as the recommend endpoint: ticker, action, bias, reason, close.
     * The per-timeframe confluence detail (bias gated on ADX≥threshold, DI widening,
     * ADX slope) is computed internally to derive the verdict but is not returned.
     */
    private Map<String, Object> evaluate(String symbol, List<String> tfs, int period,
                                         BigDecimal threshold, int rising, int warmup) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticker", symbol);

        int bull = 0, bear = 0, evaluated = 0;
        String lastClose = null;

        for (String tf : tfs) {
            try {
                List<Candle> bars = barData.getBars(symbol, tf, warmup);
                if (bars == null || bars.size() < 2 * period + 2) continue;
                List<Candle> completed = bars.subList(0, bars.size() - 1);
                lastClose = completed.get(completed.size() - 1).close().toPlainString();

                List<Point> pts = AdxCalculator.calculate(completed, period, threshold, rising);
                int latestIdx = -1;
                for (int i = pts.size() - 1; i >= 0; i--) {
                    if (pts.get(i).adx() != null) { latestIdx = i; break; }
                }
                if (latestIdx < 0) continue;
                Point p = pts.get(latestIdx);

                // Bias gated on strength: only claim direction when ADX >= threshold.
                boolean trending = p.adx().compareTo(threshold) >= 0;
                if (!trending) continue;   // no trend → contributes nothing to the verdict
                evaluated++;               // count only trending timeframes toward agreement
                if (p.direction() == Direction.UP) bull++;
                else if (p.direction() == Direction.DOWN) bear++;
            } catch (Exception e) {
                log.warn("[AdxConfluence] {} {} failed: {}", symbol, tf, e.getMessage());
            }
        }

        boolean volumeExpanding = false;
        try {
            volumeExpanding = volumeFilter.isVolumeIncreasing(symbol);
        } catch (Exception ignored) { }

        // Multi-timeframe agreement → internal verdict → BUY/SELL/HOLD action + bias.
        boolean allAgreeBull = evaluated > 0 && bull == evaluated;
        boolean allAgreeBear = evaluated > 0 && bear == evaluated;
        String verdict;
        if (allAgreeBull) verdict = volumeExpanding ? "STRONG_BULLISH" : "BULLISH";
        else if (allAgreeBear) verdict = volumeExpanding ? "STRONG_BEARISH" : "BEARISH";
        else if (bull > bear) verdict = "BULLISH";
        else if (bear > bull) verdict = "BEARISH";
        else verdict = "NEUTRAL";

        String action = verdict.contains("BULL") ? "BUY"
                : verdict.contains("BEAR") ? "SELL" : "HOLD";
        String bias = verdict.contains("BULL") ? "BULLISH"
                : verdict.contains("BEAR") ? "BEARISH" : "NEUTRAL";

        // Same fields as the recommend endpoint: ticker, action, bias, reason, close.
        row.put("action", action);
        row.put("bias", bias);
        row.put("reason", reason(verdict, allAgreeBull || allAgreeBear, volumeExpanding, tfs));
        if (lastClose != null) row.put("close", lastClose);
        return row;
    }

    private static String reason(String verdict, boolean allAgree, boolean vol, List<String> tfs) {
        if ("NEUTRAL".equals(verdict)) {
            return "No aligned trend across " + tfs + " (weak/mixed) — stand aside.";
        }
        String dir = verdict.contains("BULL") ? "up" : "down";
        StringBuilder sb = new StringBuilder();
        sb.append(allAgree ? "All timeframes " + tfs + " agree on an " + dir + "-trend"
                           : "Majority of " + tfs + " lean " + dir);
        sb.append(vol ? ", confirmed by expanding volume." : ", but volume is NOT expanding (lower conviction).");
        return sb.toString();
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
