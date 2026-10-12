package com.trading.llm;

import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw market data into a compact, information-dense {@link LlmBriefing} for the LLM.
 *
 * <p><b>Why distill instead of dumping bars?</b> A small local model reasons poorly over
 * hundreds of raw OHLCV rows — it blows the context window, is slow, and the signal drowns
 * in noise. So this builder does the quantitative work in Java (fetch bars, compute
 * Supertrend) and hands the model a short list of <em>facts</em>: the current trend and
 * price-vs-Supertrend separation (in ATR units), the latest flip and its age, the move's
 * travel and range over the window, the volume trend, and a small tail of the most recent
 * bars with their Supertrend state. The model then reasons over that briefing — exactly the
 * {@link LlmBriefing} contract (facts come from the deterministic layer).</p>
 *
 * <p>All distances are expressed in ATR units where it aids cross-symbol comparison, mirroring
 * the Swift Trend conventions already used across the app. Prices use {@link BigDecimal}.</p>
 */
@Component
public class MarketBriefingBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarketBriefingBuilder.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataService marketData;
    private final SupertrendService supertrend;

    public MarketBriefingBuilder(MarketDataService marketData, SupertrendService supertrend) {
        this.marketData = marketData;
        this.supertrend = supertrend;
    }

    /** Result of building a briefing: the briefing itself plus the raw inputs for the API echo. */
    public record Result(LlmBriefing briefing, List<Candle> candles, List<Point> points) {}

    /**
     * Fetches {@code count} bars for {@code ticker} at {@code timespan}/{@code sessions},
     * computes Supertrend, and distills everything into a briefing.
     *
     * @throws com.trading.service.MarketDataException if the broker call fails
     * @throws IllegalArgumentException                if timespan/sessions are invalid
     * @throws IllegalStateException                   if there is no / not enough data
     */
    public Result build(String ticker, int count, String timespan, String sessions, String sessionLabel) {
        List<Candle> candles = marketData.fetchHistoricalBars(ticker, count, timespan, sessions);
        if (candles.isEmpty()) {
            throw new IllegalStateException("No market data available for " + ticker);
        }
        int length = supertrend.length();
        if (candles.size() < length + 1) {
            throw new IllegalStateException(
                    "Not enough bars (" + candles.size() + ") for Supertrend length=" + length);
        }

        List<Point> points = supertrend.calculate(candles);
        List<String> facts = distill(ticker, timespan, candles, points);

        log.info("[MarketBriefingBuilder] built {}-fact briefing for {} {} over {} bars",
                facts.size(), ticker, timespan, candles.size());

        LlmBriefing briefing = new LlmBriefing(ticker, timespan, sessionLabel, facts);
        return new Result(briefing, candles, points);
    }

    /** Distils the candle + Supertrend series into dense, plain-language fact strings. */
    private List<String> distill(String ticker, String timespan, List<Candle> candles, List<Point> points) {
        List<String> facts = new ArrayList<>();
        int n = candles.size();

        Candle first = candles.get(0);
        Candle last = candles.get(n - 1);
        BigDecimal firstClose = first.close();
        BigDecimal lastClose = last.close();

        // Latest Supertrend point that actually has a value (skip warm-up tail just in case).
        Point latest = null;
        int latestIdx = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (points.get(i).supertrend() != null) { latest = points.get(i); latestIdx = i; break; }
        }

        BigDecimal atr = latest != null ? latest.atr() : null;

        // 1) Window overview.
        facts.add("Window: %d %s bars for %s.".formatted(n, timespan, ticker));
        facts.add("Current price (last close): %s.".formatted(plain(lastClose)));

        // 2) Price travel over the window (absolute + %).
        BigDecimal change = lastClose.subtract(firstClose, MC);
        BigDecimal changePct = firstClose.signum() == 0 ? BigDecimal.ZERO
                : change.divide(firstClose, MC).multiply(BigDecimal.valueOf(100), MC);
        facts.add("Over the window price moved from %s to %s (%s%s, %s%%).".formatted(
                plain(firstClose), plain(lastClose),
                change.signum() >= 0 ? "+" : "", plain2(change), plain2(changePct)));

        // 3) High / low of the window.
        BigDecimal hi = last.high(), lo = last.low();
        for (Candle c : candles) {
            if (c.high().compareTo(hi) > 0) hi = c.high();
            if (c.low().compareTo(lo) < 0) lo = c.low();
        }
        facts.add("Window high %s, low %s; last close sits %s%% above the window low.".formatted(
                plain(hi), plain(lo),
                lo.signum() == 0 ? "?" : plain2(lastClose.subtract(lo, MC)
                        .divide(lo, MC).multiply(BigDecimal.valueOf(100), MC))));

        // 4) Supertrend state: direction, line value, separation in ATR units.
        if (latest != null && latest.direction() != null) {
            facts.add("Supertrend (length %d, factor %s) is %s; line at %s.".formatted(
                    supertrend.length(), plain(supertrend.factor()),
                    latest.direction().name(), plain(latest.supertrend())));

            BigDecimal sep = lastClose.subtract(latest.supertrend(), MC).abs();
            if (atr != null && atr.signum() > 0) {
                BigDecimal sepAtr = sep.divide(atr, MC);
                String side = latest.direction() == Direction.UP ? "above (support)" : "below (resistance)";
                facts.add("Price is %s ATR %s the Supertrend line (ATR=%s).".formatted(
                        plain2(sepAtr), side, plain(atr)));
            } else {
                facts.add("Price-to-Supertrend separation: %s (ATR unavailable).".formatted(plain(sep)));
            }
        } else {
            facts.add("Supertrend has no value yet (insufficient warm-up).");
        }

        // 5) Most recent flip and its age (bars since).
        int lastFlipIdx = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (points.get(i).flip()) { lastFlipIdx = i; break; }
        }
        if (lastFlipIdx >= 0) {
            int barsSince = (latestIdx >= 0 ? latestIdx : n - 1) - lastFlipIdx;
            Direction flipDir = points.get(lastFlipIdx).direction();
            facts.add("Most recent Supertrend flip was %d bar(s) ago, to %s.".formatted(
                    Math.max(0, barsSince), flipDir == null ? "?" : flipDir.name()));
        } else {
            facts.add("No Supertrend flip within the window (one continuous trend).");
        }

        // 6) Count flips in the window (whipsaw sense).
        int flips = 0;
        for (Point p : points) if (p.flip()) flips++;
        facts.add("Supertrend flipped %d time(s) across the window%s.".formatted(
                flips, flips >= 4 ? " (choppy/whipsaw-prone)" : flips == 0 ? " (very steady)" : ""));

        // 7) Volume trend: recent half vs older half average.
        if (n >= 4) {
            int mid = n / 2;
            double olderAvg = avgVolume(candles, 0, mid);
            double recentAvg = avgVolume(candles, mid, n);
            String dir = recentAvg > olderAvg * 1.1 ? "rising"
                    : recentAvg < olderAvg * 0.9 ? "falling" : "flat";
            facts.add("Average volume is %s (recent half %.0f vs older half %.0f).".formatted(
                    dir, recentAvg, olderAvg));
        }

        // 8) Compact tail: the last few bars with their Supertrend direction/flip.
        int tail = Math.min(6, n);
        StringBuilder sb = new StringBuilder("Recent bars (oldest→newest, close / ST-dir / flip): ");
        for (int i = n - tail; i < n; i++) {
            Point p = points.get(i);
            sb.append(plain(candles.get(i).close()))
              .append('/')
              .append(p.direction() == null ? "-" : (p.direction() == Direction.UP ? "UP" : "DN"))
              .append(p.flip() ? "/FLIP" : "")
              .append(i == n - 1 ? "" : ", ");
        }
        facts.add(sb.toString());

        return facts;
    }

    private static double avgVolume(List<Candle> candles, int fromInc, int toExc) {
        long sum = 0; int c = 0;
        for (int i = fromInc; i < toExc; i++) { sum += candles.get(i).volume(); c++; }
        return c == 0 ? 0 : (double) sum / c;
    }

    /** Full-precision plain string (no scientific notation). */
    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    /** 2-decimal plain string for ratios/deltas. */
    private static String plain2(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
