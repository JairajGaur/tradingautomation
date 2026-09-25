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
        for (String symbol : symbols) {
            results.add(evaluate(symbol, tfs, effPeriod, effThreshold, effRising, warmup));
        }

        if (explicit != null && symbols.size() == 1) {
            return ResponseEntity.ok(results.get(0));
        }
        // Highest score first.
        results.sort((a, b) -> ((Integer) b.get("score")) - ((Integer) a.get("score")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", scannedUniverse ? "universe" : "requested");
        body.put("timeframes", tfs);
        body.put("evaluated", results.size());
        body.put("results", results);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Per-ticker confluence across all requested timeframes + volume. */
    private Map<String, Object> evaluate(String symbol, List<String> tfs, int period,
                                         BigDecimal threshold, int rising, int warmup) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticker", symbol);

        List<Map<String, Object>> perTf = new ArrayList<>();
        int bull = 0, bear = 0, evaluated = 0;
        int score = 0;

        for (String tf : tfs) {
            Map<String, Object> tfRow = new LinkedHashMap<>();
            tfRow.put("timeframe", tf);
            try {
                List<Candle> bars = barData.getBars(symbol, tf, warmup);
                if (bars == null || bars.size() < 2 * period + 2) {
                    tfRow.put("bias", "NEUTRAL");
                    tfRow.put("note", "insufficient data");
                    perTf.add(tfRow);
                    continue;
                }
                List<Candle> completed = bars.subList(0, bars.size() - 1);
                List<Point> pts = AdxCalculator.calculate(completed, period, threshold, rising);

                int latestIdx = -1;
                for (int i = pts.size() - 1; i >= 0; i--) {
                    if (pts.get(i).adx() != null) { latestIdx = i; break; }
                }
                if (latestIdx < 0) {
                    tfRow.put("bias", "NEUTRAL");
                    tfRow.put("note", "ADX warming up");
                    perTf.add(tfRow);
                    continue;
                }
                Point p = pts.get(latestIdx);
                evaluated++;

                boolean trending = p.adx().compareTo(threshold) >= 0;

                // Bias gated on strength: only claim a direction when ADX >= threshold.
                String bias = "NEUTRAL";
                if (trending && p.direction() == Direction.UP) bias = "BULLISH";
                else if (trending && p.direction() == Direction.DOWN) bias = "BEARISH";

                // DI spread + widening.
                BigDecimal spread = p.plusDi().subtract(p.minusDi()).abs();
                Boolean widening = null;
                if (latestIdx - 1 >= 0 && pts.get(latestIdx - 1).plusDi() != null) {
                    Point prev = pts.get(latestIdx - 1);
                    BigDecimal prevSpread = prev.plusDi().subtract(prev.minusDi()).abs();
                    widening = spread.compareTo(prevSpread) > 0;
                }

                // ADX slope across the rising lookback.
                Boolean adxRising = null;
                int backIdx = latestIdx - rising;
                if (backIdx >= 0 && pts.get(backIdx).adx() != null) {
                    adxRising = p.adx().compareTo(pts.get(backIdx).adx()) > 0;
                }

                // ADXR = (ADX now + ADX `period` bars ago) / 2.
                BigDecimal adxr = null;
                int adxrBack = latestIdx - period;
                if (adxrBack >= 0 && pts.get(adxrBack).adx() != null) {
                    adxr = p.adx().add(pts.get(adxrBack).adx())
                            .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                }

                // Per-timeframe confirmations → up to 3 points each (trending, widening, slope),
                // signed by direction so opposing timeframes cancel.
                int tfPoints = 0;
                if (trending) tfPoints++;
                if (Boolean.TRUE.equals(widening)) tfPoints++;
                if (Boolean.TRUE.equals(adxRising)) tfPoints++;
                if ("BULLISH".equals(bias)) { bull++; score += tfPoints; }
                else if ("BEARISH".equals(bias)) { bear++; score += tfPoints; }

                tfRow.put("adx", p.adx().stripTrailingZeros().toPlainString());
                tfRow.put("adxr", adxr == null ? null : adxr.stripTrailingZeros().toPlainString());
                tfRow.put("plusDi", p.plusDi().stripTrailingZeros().toPlainString());
                tfRow.put("minusDi", p.minusDi().stripTrailingZeros().toPlainString());
                tfRow.put("diSpread", spread.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
                tfRow.put("diWidening", widening);
                tfRow.put("adxRising", adxRising);
                tfRow.put("trending", trending);
                tfRow.put("bias", bias);
                tfRow.put("pattern", p.pattern() == null ? null : p.pattern().name());
            } catch (Exception e) {
                log.warn("[AdxConfluence] {} {} failed: {}", symbol, tf, e.getMessage());
                tfRow.put("bias", "NEUTRAL");
                tfRow.put("note", "evaluation error");
            }
            perTf.add(tfRow);
        }

        // Volume expansion on the base (first) timeframe — one confirmation point.
        boolean volumeExpanding = false;
        try {
            volumeExpanding = volumeFilter.isVolumeIncreasing(symbol);
        } catch (Exception ignored) { }
        if (volumeExpanding) score += 1;

        // Multi-timeframe agreement → the verdict.
        String verdict;
        boolean allAgreeBull = evaluated > 0 && bull == evaluated;
        boolean allAgreeBear = evaluated > 0 && bear == evaluated;
        if (allAgreeBull) verdict = volumeExpanding ? "STRONG_BULLISH" : "BULLISH";
        else if (allAgreeBear) verdict = volumeExpanding ? "STRONG_BEARISH" : "BEARISH";
        else if (bull > bear) verdict = "BULLISH";
        else if (bear > bull) verdict = "BEARISH";
        else verdict = "NEUTRAL";

        row.put("verdict", verdict);
        row.put("score", score);          // higher = more confirmations aligned
        row.put("volumeExpanding", volumeExpanding);
        row.put("timeframeAgreement", (allAgreeBull || allAgreeBear)
                ? "ALL_AGREE" : (bull > 0 && bear > 0 ? "CONFLICTED" : "MIXED"));
        row.put("byTimeframe", perTf);
        row.put("reason", reason(verdict, allAgreeBull || allAgreeBear, volumeExpanding, tfs));
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
