package com.trading.indicator;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless utility for computing Wilder's <b>Directional Movement</b> system over a
 * series of OHLC candles: <b>+DI</b>, <b>−DI</b> (direction) and <b>ADX</b> (trend
 * strength), plus the <b>DI crossover</b> signal and two higher-level trend patterns.
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>period</b> — the DI/ADX lookback (Wilder default 14).</li>
 * </ul>
 *
 * <h2>Algorithm (Wilder, matching standard charting platforms)</h2>
 * <ol>
 *   <li><b>Directional Movement</b> per bar:
 *       {@code upMove = high - prevHigh}, {@code downMove = prevLow - low}.
 *       {@code +DM = (upMove > downMove && upMove > 0) ? upMove : 0};
 *       {@code −DM = (downMove > upMove && downMove > 0) ? downMove : 0}.</li>
 *   <li><b>True Range (TR)</b>: {@code max(high-low, |high-prevClose|, |low-prevClose|)}.</li>
 *   <li><b>Wilder smoothing (RMA)</b> of TR, +DM and −DM: the first smoothed value is the
 *       simple sum of the first {@code period} raw values; thereafter
 *       {@code smoothed = priorSmoothed - (priorSmoothed / period) + current}.</li>
 *   <li><b>Directional Indicators</b>: {@code +DI = 100 * smoothed+DM / smoothedTR},
 *       {@code −DI = 100 * smoothed−DM / smoothedTR}.</li>
 *   <li><b>DX</b> = {@code 100 * |+DI − −DI| / (+DI + −DI)}.</li>
 *   <li><b>ADX</b> = Wilder RMA of DX: seeded as the average of the first {@code period}
 *       DX values, then {@code ADX = (priorADX*(period-1) + DX) / period}.</li>
 * </ol>
 *
 * <h2>Signals flagged</h2>
 * <ul>
 *   <li><b>DI crossover</b> — {@code +DI} crossing above {@code −DI} is a bullish up-turn;
 *       {@code −DI} crossing above {@code +DI} is a bearish down-turn.</li>
 *   <li><b>LOW_ADX_RISING</b> — ADX below the trend threshold but rising over the last few
 *       bars: a trend may be waking up before it is confirmed. Direction from +DI vs −DI.</li>
 *   <li><b>HIGH_ADX_REVERSAL</b> — a DI crossover occurring while ADX is high (strong
 *       existing trend): a meaningful reversal rather than noise.</li>
 * </ul>
 *
 * <p>Arithmetic uses {@link BigDecimal} with {@link MathContext#DECIMAL128}.</p>
 */
public final class AdxCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int VALUE_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** ADX at/above this reads as a real (trending) market; below it is weak/ranging. */
    public static final BigDecimal DEFAULT_TREND_THRESHOLD = BigDecimal.valueOf(25);
    /** How many bars back ADX is compared against to judge "rising". */
    public static final int DEFAULT_RISING_LOOKBACK = 3;

    private AdxCalculator() {
        // utility class
    }

    /** Dominant direction implied by +DI vs −DI, or the direction of a fresh DI cross. */
    public enum Direction { UP, DOWN, FLAT }

    /** Higher-level trend pattern flagged on a bar (null when neither applies). */
    public enum Pattern {
        /** ADX low but rising — a trend may be starting; trade the anticipation. */
        LOW_ADX_RISING,
        /** Fresh DI crossover while ADX is high — a confirmed reversal of a strong trend. */
        HIGH_ADX_REVERSAL
    }

    /** Plain-English bucket for the ADX trend-strength reading. */
    public enum Strength {
        /** ADX &lt; 20 — no real trend; choppy / ranging. */
        NO_TREND,
        /** 20 ≤ ADX &lt; 25 — a trend may be forming but isn't confirmed. */
        WEAK,
        /** 25 ≤ ADX &lt; 40 — a healthy, tradeable trend. */
        STRONG,
        /** 40 ≤ ADX &lt; 50 — a very strong trend. */
        VERY_STRONG,
        /** ADX ≥ 50 — an extreme trend (often near exhaustion). */
        EXTREME
    }

    /** Buckets an ADX value into a {@link Strength}, or null when ADX is null. */
    public static Strength strengthOf(BigDecimal adx) {
        if (adx == null) return null;
        double v = adx.doubleValue();
        if (v < 20) return Strength.NO_TREND;
        if (v < 25) return Strength.WEAK;
        if (v < 40) return Strength.STRONG;
        if (v < 50) return Strength.VERY_STRONG;
        return Strength.EXTREME;
    }

    /**
     * Builds a one-line, human-readable summary of the trend from a computed point,
     * e.g. {@code "Strong UP trend (ADX 32.5, gaining strength). +DI above −DI."}.
     * Returns a warm-up note when ADX isn't available yet.
     *
     * @param p           the point to describe (typically the latest)
     * @param prevAdx     the ADX a few bars earlier (for "gaining/losing strength"); may be null
     * @return a readable sentence; never null
     */
    public static String describe(Point p, BigDecimal prevAdx) {
        if (p == null) {
            return "No data.";
        }
        if (p.adx() == null || p.direction() == null) {
            return "Not enough history yet — ADX still warming up.";
        }

        Strength s = strengthOf(p.adx());
        Direction dir = p.direction();

        // Direction phrase.
        String dirWord = switch (dir) {
            case UP -> "UP";
            case DOWN -> "DOWN";
            case FLAT -> "FLAT";
        };

        // Strength phrase.
        String strengthWord = switch (s) {
            case NO_TREND -> "No clear";
            case WEAK -> "Weak / forming";
            case STRONG -> "Strong";
            case VERY_STRONG -> "Very strong";
            case EXTREME -> "Extreme";
        };

        // Slope phrase (is the trend gaining or losing strength?).
        String slope = "";
        if (prevAdx != null) {
            int cmp = p.adx().compareTo(prevAdx);
            if (cmp > 0) slope = ", gaining strength";
            else if (cmp < 0) slope = ", losing strength";
            else slope = ", flat strength";
        }

        StringBuilder sb = new StringBuilder();
        if (s == Strength.NO_TREND || dir == Direction.FLAT) {
            // Weak/rangey — lead with that rather than a false directional claim.
            sb.append(strengthWord).append(" trend / choppy (ADX ")
              .append(p.adx().stripTrailingZeros().toPlainString()).append(slope).append("). ")
              .append(dir == Direction.UP ? "+DI slightly above −DI."
                    : dir == Direction.DOWN ? "−DI slightly above +DI."
                    : "+DI and −DI level.");
        } else {
            sb.append(strengthWord).append(' ').append(dirWord).append(" trend (ADX ")
              .append(p.adx().stripTrailingZeros().toPlainString()).append(slope).append("). ")
              .append(dir == Direction.UP ? "+DI above −DI (buyers in control)."
                                           : "−DI above +DI (sellers in control).");
        }

        // Append any fresh signal.
        if (p.diCross() == Direction.UP) {
            sb.append(" +DI just crossed above −DI (bullish turn).");
        } else if (p.diCross() == Direction.DOWN) {
            sb.append(" −DI just crossed above +DI (bearish turn).");
        }
        if (p.pattern() == Pattern.HIGH_ADX_REVERSAL) {
            sb.append(" ⚠ High-ADX reversal — a strong trend is flipping.");
        } else if (p.pattern() == Pattern.LOW_ADX_RISING) {
            sb.append(" A new trend may be waking up (low ADX, rising).");
        }
        return sb.toString();
    }

    /**
     * A short label for a single bar's actionable signal, for the {@code signal}
     * column in a series row. One of: {@code STRONG_UP}, {@code STRONG_DOWN},
     * {@code UP}, {@code DOWN}, {@code CHOP}, or a pattern/cross tag when one fires.
     */
    public static String signalOf(Point p) {
        if (p == null || p.adx() == null || p.direction() == null) {
            return "WARMING_UP";
        }
        if (p.pattern() == Pattern.HIGH_ADX_REVERSAL) {
            return p.direction() == Direction.UP ? "REVERSAL_UP" : "REVERSAL_DOWN";
        }
        if (p.pattern() == Pattern.LOW_ADX_RISING) {
            return p.direction() == Direction.UP ? "WAKING_UP_UP" : "WAKING_UP_DOWN";
        }
        Strength s = strengthOf(p.adx());
        if (s == Strength.NO_TREND || p.direction() == Direction.FLAT) {
            return "CHOP";
        }
        boolean strong = s == Strength.STRONG || s == Strength.VERY_STRONG || s == Strength.EXTREME;
        if (p.direction() == Direction.UP) {
            return strong ? "STRONG_UP" : "UP";
        }
        return strong ? "STRONG_DOWN" : "DOWN";
    }

    /** A simple actionable verdict derived from the ADX/DI reading. */
    public enum Recommendation { BUY, SELL, HOLD }

    /**
     * A verdict plus a one-line reason and the underlying signal, suitable for a
     * recommendation endpoint.
     *
     * @param action  BUY / SELL / HOLD
     * @param signal  the underlying {@link #signalOf(Point)} label
     * @param reason  short plain-English justification
     */
    public record Advice(Recommendation action, String signal, String reason) {}

    /**
     * Turns a computed point into a BUY / SELL / HOLD verdict.
     *
     * <p>Logic (from the direction + strength signal on the latest completed bar):</p>
     * <ul>
     *   <li><b>BUY</b> — a confirmed or emerging up-trend: {@code STRONG_UP},
     *       {@code REVERSAL_UP} (bullish DI cross while ADX high) or
     *       {@code WAKING_UP_UP} (low ADX but rising, +DI on top).</li>
     *   <li><b>SELL</b> — the bearish mirror: {@code STRONG_DOWN},
     *       {@code REVERSAL_DOWN} or {@code WAKING_UP_DOWN}.</li>
     *   <li><b>HOLD</b> — everything else: a weak directional lean ({@code UP}/{@code DOWN}
     *       with ADX &lt; 25), no trend ({@code CHOP}), or not enough data
     *       ({@code WARMING_UP}). Not worth acting on.</li>
     * </ul>
     *
     * @param p       the point to judge (typically the latest completed bar)
     * @param prevAdx ADX a few bars earlier, for the reason text; may be null
     * @return an {@link Advice}; never null
     */
    public static Advice recommend(Point p, BigDecimal prevAdx) {
        String signal = signalOf(p);
        String adxStr = (p == null || p.adx() == null)
                ? "n/a" : p.adx().stripTrailingZeros().toPlainString();
        String slope = "";
        if (p != null && p.adx() != null && prevAdx != null) {
            int c = p.adx().compareTo(prevAdx);
            slope = c > 0 ? ", strengthening" : c < 0 ? ", weakening" : "";
        }

        return switch (signal) {
            case "STRONG_UP" -> new Advice(Recommendation.BUY, signal,
                    "Strong up-trend (ADX " + adxStr + slope + "), buyers in control.");
            case "REVERSAL_UP" -> new Advice(Recommendation.BUY, signal,
                    "Bullish reversal: +DI crossed above −DI with strong ADX (" + adxStr + ").");
            case "WAKING_UP_UP" -> new Advice(Recommendation.BUY, signal,
                    "Up-trend waking up (ADX low but rising to " + adxStr + "), +DI on top.");
            case "STRONG_DOWN" -> new Advice(Recommendation.SELL, signal,
                    "Strong down-trend (ADX " + adxStr + slope + "), sellers in control.");
            case "REVERSAL_DOWN" -> new Advice(Recommendation.SELL, signal,
                    "Bearish reversal: −DI crossed above +DI with strong ADX (" + adxStr + ").");
            case "WAKING_UP_DOWN" -> new Advice(Recommendation.SELL, signal,
                    "Down-trend waking up (ADX low but rising to " + adxStr + "), −DI on top.");
            case "UP" -> new Advice(Recommendation.HOLD, signal,
                    "Up lean but weak (ADX " + adxStr + " < 25) — low conviction, wait.");
            case "DOWN" -> new Advice(Recommendation.HOLD, signal,
                    "Down lean but weak (ADX " + adxStr + " < 25) — low conviction, wait.");
            case "CHOP" -> new Advice(Recommendation.HOLD, signal,
                    "No clear trend / choppy (ADX " + adxStr + ") — stand aside.");
            default -> new Advice(Recommendation.HOLD, signal,
                    "Not enough history yet — ADX still warming up.");
        };
    }

    /**
     * Per-bar Directional-Movement output.
     *
     * @param candle    the source candle
     * @param plusDi    +DI (null during warm-up before the first DI is available)
     * @param minusDi   −DI (null during warm-up)
     * @param adx       ADX / trend strength (null until the ADX seed is reached)
     * @param diCross   direction of a DI crossover on THIS bar (UP / DOWN), or null if none
     * @param pattern   flagged trend pattern on this bar, or null
     * @param direction dominant direction from +DI vs −DI (UP / DOWN / FLAT), null during warm-up
     */
    public record Point(
            Candle candle,
            BigDecimal plusDi,
            BigDecimal minusDi,
            BigDecimal adx,
            Direction diCross,
            Pattern pattern,
            Direction direction
    ) {}

    /** Convenience overload using the default trend threshold and rising-lookback. */
    public static List<Point> calculate(List<Candle> candles, int period) {
        return calculate(candles, period, DEFAULT_TREND_THRESHOLD, DEFAULT_RISING_LOOKBACK);
    }

    /**
     * Computes the +DI / −DI / ADX series for {@code candles} (oldest-first) and flags
     * DI crossovers plus the two trend patterns.
     *
     * @param candles        OHLC candles in chronological order (oldest first)
     * @param period         DI/ADX lookback (>= 1)
     * @param trendThreshold ADX level separating weak/ranging from trending (e.g. 25)
     * @param risingLookback bars back ADX is compared against to judge "rising" (>= 1)
     * @return one {@link Point} per input candle, same order; warm-up bars carry nulls.
     */
    public static List<Point> calculate(List<Candle> candles, int period,
                                        BigDecimal trendThreshold, int risingLookback) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("candles must not be null or empty");
        }
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1");
        }
        if (trendThreshold == null || trendThreshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("trendThreshold must be >= 0");
        }
        if (risingLookback < 1) {
            throw new IllegalArgumentException("risingLookback must be >= 1");
        }

        int n = candles.size();

        // 1. Raw TR, +DM, −DM per bar (index 0 has no prev bar → all zero / no DM).
        BigDecimal[] tr = new BigDecimal[n];
        BigDecimal[] plusDm = new BigDecimal[n];
        BigDecimal[] minusDm = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            if (i == 0) {
                tr[i] = c.high().subtract(c.low(), MC);
                plusDm[i] = BigDecimal.ZERO;
                minusDm[i] = BigDecimal.ZERO;
                continue;
            }
            Candle prev = candles.get(i - 1);
            BigDecimal highLow = c.high().subtract(c.low(), MC);
            BigDecimal hC = c.high().subtract(prev.close(), MC).abs();
            BigDecimal lC = c.low().subtract(prev.close(), MC).abs();
            tr[i] = highLow.max(hC).max(lC);

            BigDecimal upMove = c.high().subtract(prev.high(), MC);
            BigDecimal downMove = prev.low().subtract(c.low(), MC);
            boolean upPos = upMove.signum() > 0;
            boolean downPos = downMove.signum() > 0;
            plusDm[i] = (upPos && upMove.compareTo(downMove) > 0) ? upMove : BigDecimal.ZERO;
            minusDm[i] = (downPos && downMove.compareTo(upMove) > 0) ? downMove : BigDecimal.ZERO;
        }

        // 2. Wilder-smoothed TR, +DM, −DM → +DI, −DI, DX per bar.
        //    The first smoothed value (at index `period`) is the sum of the first
        //    `period` raw values covering indices 1..period (index 0 DM is zero anyway).
        BigDecimal[] plusDiArr = new BigDecimal[n];
        BigDecimal[] minusDiArr = new BigDecimal[n];
        BigDecimal[] dxArr = new BigDecimal[n];

        BigDecimal smTr = null;
        BigDecimal smPlus = null;
        BigDecimal smMinus = null;
        BigDecimal periodBd = BigDecimal.valueOf(period);

        BigDecimal sumTr = BigDecimal.ZERO;
        BigDecimal sumPlus = BigDecimal.ZERO;
        BigDecimal sumMinus = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            if (i < period) {
                // Accumulate the seed sum. DI/DX not available yet.
                sumTr = sumTr.add(tr[i], MC);
                sumPlus = sumPlus.add(plusDm[i], MC);
                sumMinus = sumMinus.add(minusDm[i], MC);
                if (i == period - 1) {
                    // Seed complete but we still need one more bar's worth of raw
                    // DM/TR before the first DI (Wilder counts from index `period`).
                    smTr = sumTr;
                    smPlus = sumPlus;
                    smMinus = sumMinus;
                }
                continue;
            }
            // Wilder RMA update: smoothed = prior - prior/period + current.
            smTr = smTr.subtract(smTr.divide(periodBd, MC), MC).add(tr[i], MC);
            smPlus = smPlus.subtract(smPlus.divide(periodBd, MC), MC).add(plusDm[i], MC);
            smMinus = smMinus.subtract(smMinus.divide(periodBd, MC), MC).add(minusDm[i], MC);

            BigDecimal plusDi = smTr.signum() == 0 ? BigDecimal.ZERO
                    : HUNDRED.multiply(smPlus, MC).divide(smTr, MC);
            BigDecimal minusDi = smTr.signum() == 0 ? BigDecimal.ZERO
                    : HUNDRED.multiply(smMinus, MC).divide(smTr, MC);
            plusDiArr[i] = plusDi;
            minusDiArr[i] = minusDi;

            BigDecimal diSum = plusDi.add(minusDi, MC);
            dxArr[i] = diSum.signum() == 0 ? BigDecimal.ZERO
                    : HUNDRED.multiply(plusDi.subtract(minusDi, MC).abs(), MC).divide(diSum, MC);
        }

        // 3. ADX = Wilder RMA of DX. DX first exists at index `period`; seed ADX with
        //    the average of the first `period` DX values → first ADX at index 2*period-1.
        BigDecimal[] adxArr = new BigDecimal[n];
        BigDecimal adx = null;
        int dxStart = period;                 // first index with a DX value
        int adxSeedEnd = 2 * period - 1;      // index where the ADX seed completes
        BigDecimal dxSum = BigDecimal.ZERO;
        for (int i = dxStart; i < n; i++) {
            if (dxArr[i] == null) continue;
            if (i < adxSeedEnd) {
                dxSum = dxSum.add(dxArr[i], MC);
            } else if (i == adxSeedEnd) {
                dxSum = dxSum.add(dxArr[i], MC);
                adx = dxSum.divide(periodBd, MC);
                adxArr[i] = adx;
            } else {
                adx = adx.multiply(periodBd.subtract(BigDecimal.ONE, MC), MC)
                        .add(dxArr[i], MC)
                        .divide(periodBd, MC);
                adxArr[i] = adx;
            }
        }

        // 4. Assemble points, flagging DI crossovers and the two patterns.
        List<Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            BigDecimal plusDi = plusDiArr[i];
            BigDecimal minusDi = minusDiArr[i];
            BigDecimal adxVal = adxArr[i];

            if (plusDi == null || minusDi == null) {
                out.add(new Point(c, null, null,
                        adxVal == null ? null : adxVal.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
                        null, null, null));
                continue;
            }

            // Dominant direction from +DI vs −DI.
            int cmp = plusDi.compareTo(minusDi);
            Direction direction = cmp > 0 ? Direction.UP : (cmp < 0 ? Direction.DOWN : Direction.FLAT);

            // DI crossover vs the previous bar that had both DIs.
            Direction diCross = null;
            BigDecimal prevPlus = plusDiArr[i - 1];
            BigDecimal prevMinus = minusDiArr[i - 1];
            if (prevPlus != null && prevMinus != null) {
                boolean crossedUp = plusDi.compareTo(minusDi) > 0 && prevPlus.compareTo(prevMinus) <= 0;
                boolean crossedDown = minusDi.compareTo(plusDi) > 0 && prevMinus.compareTo(prevPlus) <= 0;
                if (crossedUp) diCross = Direction.UP;
                else if (crossedDown) diCross = Direction.DOWN;
            }

            // Pattern flags (need ADX).
            Pattern pattern = null;
            if (adxVal != null) {
                boolean adxLow = adxVal.compareTo(trendThreshold) < 0;
                boolean adxHigh = !adxLow;

                // Rising: ADX greater than it was `risingLookback` bars ago (need that bar's ADX).
                boolean adxRising = false;
                int backIdx = i - risingLookback;
                if (backIdx >= 0 && adxArr[backIdx] != null) {
                    adxRising = adxVal.compareTo(adxArr[backIdx]) > 0;
                }

                if (adxHigh && diCross != null) {
                    pattern = Pattern.HIGH_ADX_REVERSAL;
                } else if (adxLow && adxRising) {
                    pattern = Pattern.LOW_ADX_RISING;
                }
            }

            out.add(new Point(c,
                    plusDi.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
                    minusDi.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
                    adxVal == null ? null : adxVal.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
                    diCross, pattern, direction));
        }

        return out;
    }

    /** Convenience: the last point that has an ADX value, or null if none computed. */
    public static Point latest(List<Candle> candles, int period) {
        List<Point> pts = calculate(candles, period);
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).adx() != null) {
                return pts.get(i);
            }
        }
        return null;
    }
}
