package com.trading.service;

import com.trading.config.WebullProperties;
import com.trading.indicator.AdxCalculator;
import com.trading.indicator.AdxCalculator.Direction;
import com.trading.indicator.AdxCalculator.Point;
import com.trading.indicator.AdxService;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared multi-confirmation trend evaluation ("confluence"), used by both the
 * {@code /api/adx/confluence} endpoint and the scheduled alert scanner — one source of
 * truth. Per requested timeframe (latest completed bar) it reads ADX/DI, gates bias on
 * ADX ≥ threshold, and scores DI-widening + ADX-slope; then combines across timeframes
 * plus a volume-expansion check into a verdict and a 0–100 heuristic confidence.
 */
@Service
public class ConfluenceService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceService.class);

    private final WebullProperties props;
    private final BarDataManager barData;
    private final AdxService adx;
    private final VolumeFilter volumeFilter;

    public ConfluenceService(WebullProperties props, BarDataManager barData,
                             AdxService adx, VolumeFilter volumeFilter) {
        this.props = props;
        this.barData = barData;
        this.adx = adx;
        this.volumeFilter = volumeFilter;
    }

    /** Verdict labels. */
    public static final String STRONG_BULLISH = "STRONG_BULLISH";
    public static final String STRONG_BEARISH = "STRONG_BEARISH";

    /**
     * Result of a confluence evaluation for one ticker.
     *
     * @param ticker          symbol
     * @param verdict         STRONG_BULLISH / BULLISH / NEUTRAL / BEARISH / STRONG_BEARISH
     * @param action          BUY / SELL / HOLD
     * @param bias            BULLISH / BEARISH / NEUTRAL
     * @param confidenceScore 0–100 heuristic (NOT a probability)
     * @param confidence      LOW / MEDIUM / HIGH
     * @param close           last completed close (nullable)
     * @param reason          plain-English justification
     */
    public record Result(String ticker, String verdict, String action, String bias,
                         int confidenceScore, String confidence, String close, String reason) {}

    /** Convenience overload using the configured ADX params. */
    public Result evaluate(String symbol, List<String> tfs) {
        return evaluate(symbol, tfs, adx.period(), adx.threshold(), adx.rising());
    }

    /** Full confluence evaluation for {@code symbol} across {@code tfs}. */
    public Result evaluate(String symbol, List<String> tfs, int period,
                           BigDecimal threshold, int rising) {
        int warmup = props.trading().warmupBars();
        int bull = 0, bear = 0, evaluated = 0;
        String lastClose = null;

        double rawPoints = 0.0;
        double maxPoints = 0.0;
        final double perTfMax = 5.0;

        for (String tf : tfs) {
            maxPoints += perTfMax;
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

                boolean trending = p.adx().compareTo(threshold) >= 0;
                if (!trending) continue;
                evaluated++;
                if (p.direction() == Direction.UP) bull++;
                else if (p.direction() == Direction.DOWN) bear++;

                AdxCalculator.Strength s = AdxCalculator.strengthOf(p.adx());
                rawPoints += switch (s) {
                    case STRONG -> 1.5;
                    case VERY_STRONG -> 2.5;
                    case EXTREME -> 3.0;
                    default -> 1.0;
                };
                if (latestIdx - 1 >= 0 && pts.get(latestIdx - 1).plusDi() != null) {
                    Point prev = pts.get(latestIdx - 1);
                    BigDecimal spread = p.plusDi().subtract(p.minusDi()).abs();
                    BigDecimal prevSpread = prev.plusDi().subtract(prev.minusDi()).abs();
                    if (spread.compareTo(prevSpread) > 0) rawPoints += 1.0;
                }
                int backIdx = latestIdx - rising;
                if (backIdx >= 0 && pts.get(backIdx).adx() != null
                        && p.adx().compareTo(pts.get(backIdx).adx()) > 0) {
                    rawPoints += 1.0;
                }
            } catch (Exception e) {
                log.warn("[ConfluenceService] {} {} failed: {}", symbol, tf, e.getMessage());
            }
        }

        boolean volumeExpanding = false;
        try {
            volumeExpanding = volumeFilter.isVolumeIncreasing(symbol);
        } catch (Exception ignored) { }

        boolean allAgreeBull = evaluated > 0 && bull == evaluated;
        boolean allAgreeBear = evaluated > 0 && bear == evaluated;
        String verdict;
        if (allAgreeBull) verdict = volumeExpanding ? STRONG_BULLISH : "BULLISH";
        else if (allAgreeBear) verdict = volumeExpanding ? STRONG_BEARISH : "BEARISH";
        else if (bull > bear) verdict = "BULLISH";
        else if (bear > bull) verdict = "BEARISH";
        else verdict = "NEUTRAL";

        String action = verdict.contains("BULL") ? "BUY"
                : verdict.contains("BEAR") ? "SELL" : "HOLD";
        String bias = verdict.contains("BULL") ? "BULLISH"
                : verdict.contains("BEAR") ? "BEARISH" : "NEUTRAL";

        int confidence = 0;
        if (!"NEUTRAL".equals(verdict) && !(bull > 0 && bear > 0)) {
            double base = maxPoints > 0 ? (rawPoints / maxPoints) * 80.0 : 0.0;
            double bonus = 0.0;
            if (allAgreeBull || allAgreeBear) bonus += 10.0;
            if (volumeExpanding) bonus += 10.0;
            confidence = (int) Math.round(Math.min(100.0, base + bonus));
        }
        String confLabel = confidence >= 70 ? "HIGH" : confidence >= 40 ? "MEDIUM" : "LOW";

        return new Result(symbol, verdict, action, bias, confidence, confLabel, lastClose,
                reason(verdict, allAgreeBull || allAgreeBear, volumeExpanding, tfs));
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
}
