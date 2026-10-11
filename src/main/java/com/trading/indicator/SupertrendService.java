package com.trading.indicator;

import com.trading.config.WebullProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.model.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Injectable Supertrend calculator that carries the configured {@code length} and
 * {@code factor} ({@code webull.supertrend.length} / {@code factor}, defaults 7 / 3),
 * so callers don't pass those parameters around.
 *
 * <p>Delegates the math to the stateless {@link SupertrendCalculator}. Use this bean
 * for the app's canonical Supertrend; use the static calculator directly only when
 * you need arbitrary, caller-supplied parameters (e.g. the inspection endpoint).</p>
 */
@Component
public class SupertrendService {

    private final int length;
    private final BigDecimal factor;

    public SupertrendService(WebullProperties props) {
        this.length = props.supertrend().length();
        this.factor = props.supertrend().factor();
    }

    /** Configured ATR period. */
    public int length() { return length; }

    /** Configured ATR multiplier. */
    public BigDecimal factor() { return factor; }

    /** Full Supertrend series over {@code candles} using the configured params. */
    public List<Point> calculate(List<Candle> candles) {
        return SupertrendCalculator.calculate(candles, length, factor);
    }

    /** Most recent computed Supertrend point (configured params), or null. */
    public Point latest(List<Candle> candles) {
        return SupertrendCalculator.latest(candles, length, factor);
    }

    /**
     * Direction of the latest <em>completed</em> bar (drops the last, possibly
     * in-progress candle), or {@code null} when it can't be computed.
     *
     * @param candles OHLC candles oldest-first (should include the current bar)
     */
    public Direction latestCompletedDirection(List<Candle> candles) {
        if (candles == null || candles.size() < length + 1) {
            return null;
        }
        List<Candle> completed = candles.subList(0, candles.size() - 1);
        Point p = SupertrendCalculator.latest(completed, length, factor);
        return p == null ? null : p.direction();
    }

    /**
     * Supertrend-line-slope confluence check: returns true when the Supertrend LINE has been
     * advancing in {@code dir} on ANY of the last {@code bars} completed bars.
     *
     * <p>For each candidate bar the line's own movement is measured over the prior
     * {@code lookback} bars, in ATR units (rising for UP / falling for DOWN), using the
     * configured {@code length}/{@code factor}. A bar "confirms" when that advance is
     * {@code >= minSlopeAtr}. Returning true if <b>any</b> of the last {@code bars} confirms
     * makes the gate lenient: a currently-flat line still passes if it moved in-direction on a
     * recent bar. Mirrors the Swift scanner's st-slope semantics (span + ATR threshold).</p>
     *
     * <p>Operates on COMPLETED bars only (drops the last, possibly in-progress candle).</p>
     *
     * @param candles     OHLC candles oldest-first (should include the current in-progress bar)
     * @param dir         the direction to confirm (UP for an ORB up break, DOWN for down)
     * @param lookback    span in bars for one slope measurement (>= 1)
     * @param minSlopeAtr required line advance over the lookback, in ATR units
     * @param bars        the "N": how many of the latest completed bars to test (>= 1)
     * @return true if any of the last {@code bars} shows the line advancing >= threshold in {@code dir}
     */
    public boolean slopeConfirms(List<Candle> candles, Direction dir,
                                 int lookback, BigDecimal minSlopeAtr, int bars) {
        if (candles == null || dir == null || minSlopeAtr == null) return false;
        int look = Math.max(1, lookback);
        int n = Math.max(1, bars);
        if (candles.size() < length + 2) return false;

        List<Candle> completed = candles.subList(0, candles.size() - 1);
        List<Point> pts = SupertrendCalculator.calculate(completed, length, factor);

        // Index of the latest point with a computed Supertrend.
        int cur = -1;
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).supertrend() != null) { cur = i; break; }
        }
        if (cur < 0) return false;

        // Test the last N bars; pass if ANY of them advances >= threshold in `dir`.
        for (int b = cur; b > cur - n && b >= 0; b--) {
            BigDecimal slope = slopeAtrOverLookback(pts, b, dir, look);
            if (slope != null && slope.compareTo(minSlopeAtr) >= 0) return true;
        }
        return false;
    }

    /**
     * The Supertrend line's movement in {@code dir} over up to {@code lookback} bars ending at
     * {@code at}, in ATR units (signed so trend-direction movement is positive). Returns null
     * when there is no valid prior Supertrend/ATR to compare. (Standalone twin of the Swift
     * detector's internal slope helper, so both share the same semantics without a dependency.)
     */
    private static BigDecimal slopeAtrOverLookback(List<Point> pts, int at, Direction dir, int lookback) {
        if (at < 0 || at >= pts.size()) return null;
        Point atPt = pts.get(at);
        BigDecimal atr = atPt.atr();
        BigDecimal stNow = atPt.supertrend();
        if (atr == null || atr.signum() <= 0 || stNow == null) return null;

        int start = Math.max(0, at - Math.max(1, lookback));
        while (start < at && pts.get(start).supertrend() == null) start++;
        if (start >= at) return null;
        BigDecimal stStart = pts.get(start).supertrend();
        if (stStart == null) return null;

        BigDecimal move = (dir == Direction.UP)
                ? stNow.subtract(stStart)        // UP: line should rise
                : stStart.subtract(stNow);       // DOWN: line should fall
        return move.divide(atr, java.math.MathContext.DECIMAL128);
    }
}
