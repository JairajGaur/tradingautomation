package com.trading.indicator;

import com.trading.model.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless utility for computing the <b>Supertrend</b> indicator over a series
 * of OHLC candles.
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>length</b> — ATR period (Webull default 10; user uses 7).</li>
 *   <li><b>factor</b> — ATR multiplier (Webull default 3).</li>
 * </ul>
 *
 * <h2>Algorithm (matches Webull's charting definition)</h2>
 * <ol>
 *   <li><b>True Range (TR)</b> for each bar:
 *       {@code max(high-low, |high-prevClose|, |low-prevClose|)}.</li>
 *   <li><b>ATR</b> — Wilder's smoothing (RMA). The first ATR is the simple
 *       average of the first {@code length} TR values; thereafter:
 *       {@code ATR = (priorATR*(length-1) + currentTR) / length}.
 *       (For length=14 this is the {@code (priorATR*13 + TR)/14} form Webull shows.)</li>
 *   <li><b>Basic bands</b> around HL2 = (high+low)/2:
 *       {@code upperBasic = HL2 + factor*ATR}, {@code lowerBasic = HL2 - factor*ATR}.</li>
 *   <li><b>Final bands</b> are "sticky" — they only move toward price:
 *       <pre>
 *       finalUpper = (upperBasic &lt; prevFinalUpper || prevClose &gt; prevFinalUpper)
 *                        ? upperBasic : prevFinalUpper
 *       finalLower = (lowerBasic &gt; prevFinalLower || prevClose &lt; prevFinalLower)
 *                        ? lowerBasic : prevFinalLower
 *       </pre></li>
 *   <li><b>Supertrend line & direction</b>: when in an uptrend the line is the
 *       final lower band (support); in a downtrend it is the final upper band
 *       (resistance). Direction flips when close crosses the active band.</li>
 * </ol>
 *
 * <p>Arithmetic uses {@link BigDecimal} with {@link MathContext#DECIMAL128}.</p>
 */
public final class SupertrendCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int PRICE_SCALE = 8;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private SupertrendCalculator() {
        // utility class
    }

    /** Trend direction of a Supertrend bar. */
    public enum Direction { UP, DOWN }

    /**
     * Per-bar Supertrend output.
     *
     * @param candle      the source candle
     * @param atr         the Wilder ATR at this bar (null during warm-up before the first ATR)
     * @param supertrend  the Supertrend line value (null during warm-up)
     * @param direction   UP (line below price / support) or DOWN (line above price / resistance); null during warm-up
     * @param flip        true when the direction flipped versus the previous bar (the signal)
     */
    public record Point(
            Candle candle,
            BigDecimal atr,
            BigDecimal supertrend,
            Direction direction,
            boolean flip
    ) {}

    /**
     * Computes the Supertrend series for {@code candles} (oldest-first).
     *
     * @param candles OHLC candles in chronological order (oldest first)
     * @param length  ATR period (>= 1)
     * @param factor  ATR multiplier (> 0)
     * @return one {@link Point} per input candle, in the same order. Bars before
     *         the ATR seed ({@code length} bars) carry null atr/supertrend/direction.
     */
    public static List<Point> calculate(List<Candle> candles, int length, BigDecimal factor) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("candles must not be null or empty");
        }
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1");
        }
        if (factor == null || factor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("factor must be > 0");
        }

        int n = candles.size();
        List<Point> out = new ArrayList<>(n);

        // 1. True Range per bar (index 0 has no prev close → TR = high - low).
        BigDecimal[] tr = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            BigDecimal highLow = c.high().subtract(c.low(), MC);
            if (i == 0) {
                tr[i] = highLow;
            } else {
                BigDecimal prevClose = candles.get(i - 1).close();
                BigDecimal hC = c.high().subtract(prevClose, MC).abs();
                BigDecimal lC = c.low().subtract(prevClose, MC).abs();
                tr[i] = highLow.max(hC).max(lC);
            }
        }

        // State carried forward across bars.
        BigDecimal atr = null;             // Wilder ATR, seeded once `length` TRs exist
        BigDecimal prevFinalUpper = null;
        BigDecimal prevFinalLower = null;
        Direction prevDir = null;
        BigDecimal lengthBd = BigDecimal.valueOf(length);
        BigDecimal lengthMinusOne = BigDecimal.valueOf((long) length - 1);

        BigDecimal trRunningSum = BigDecimal.ZERO; // for the initial SMA seed

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);

            // 2. ATR (Wilder). Seed = SMA of first `length` TRs at index length-1.
            if (i < length - 1) {
                trRunningSum = trRunningSum.add(tr[i], MC);
                out.add(new Point(c, null, null, null, false));
                continue;
            } else if (i == length - 1) {
                trRunningSum = trRunningSum.add(tr[i], MC);
                atr = trRunningSum.divide(lengthBd, MC);
            } else {
                // ATR = (priorATR*(length-1) + currentTR) / length
                atr = atr.multiply(lengthMinusOne, MC)
                         .add(tr[i], MC)
                         .divide(lengthBd, MC);
            }

            // 3. Basic bands around HL2.
            BigDecimal hl2 = c.high().add(c.low(), MC).divide(TWO, MC);
            BigDecimal offset = factor.multiply(atr, MC);
            BigDecimal upperBasic = hl2.add(offset, MC);
            BigDecimal lowerBasic = hl2.subtract(offset, MC);

            // 4. Final (sticky) bands.
            BigDecimal finalUpper;
            BigDecimal finalLower;
            if (prevFinalUpper == null) {
                // First computed bar — no previous band to stick to.
                finalUpper = upperBasic;
                finalLower = lowerBasic;
            } else {
                BigDecimal prevClose = candles.get(i - 1).close();
                finalUpper = (upperBasic.compareTo(prevFinalUpper) < 0
                                || prevClose.compareTo(prevFinalUpper) > 0)
                        ? upperBasic : prevFinalUpper;
                finalLower = (lowerBasic.compareTo(prevFinalLower) > 0
                                || prevClose.compareTo(prevFinalLower) < 0)
                        ? lowerBasic : prevFinalLower;
            }

            // 5. Direction + Supertrend line.
            Direction dir;
            if (prevDir == null) {
                // Initialise: close above upper band → up, else down.
                dir = c.close().compareTo(finalUpper) > 0 ? Direction.UP : Direction.DOWN;
            } else if (prevDir == Direction.UP) {
                // Was up; flip down if close breaks below the (previous) lower band.
                dir = c.close().compareTo(finalLower) < 0 ? Direction.DOWN : Direction.UP;
            } else {
                // Was down; flip up if close breaks above the (previous) upper band.
                dir = c.close().compareTo(finalUpper) > 0 ? Direction.UP : Direction.DOWN;
            }

            BigDecimal supertrend = (dir == Direction.UP ? finalLower : finalUpper)
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            boolean flip = prevDir != null && dir != prevDir;

            out.add(new Point(c,
                    atr.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    supertrend, dir, flip));

            prevFinalUpper = finalUpper;
            prevFinalLower = finalLower;
            prevDir = dir;
        }

        return out;
    }

    /** Convenience: the last (most recent) Supertrend point, or null if none computed. */
    public static Point latest(List<Candle> candles, int length, BigDecimal factor) {
        List<Point> pts = calculate(candles, length, factor);
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).supertrend() != null) {
                return pts.get(i);
            }
        }
        return null;
    }
}
