package com.trading.indicator;

import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.model.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Detects a <b>swift</b> Supertrend trend — a <i>fresh</i> Supertrend direction flip
 * that is immediately backed by strong, ATR-normalised momentum. This is deliberately
 * stricter than "Supertrend is UP/DOWN": a slow grind that hugs the Supertrend line, a
 * choppy whipsaw, or an exhausted/extended move all fail the checks.
 *
 * <h2>What makes a move "swift"</h2>
 * Evaluated on the latest <em>completed</em> Supertrend {@link Point} of a series:
 * <ol>
 *   <li><b>Fresh flip</b> — the direction flipped within the last {@code flipWithinBars}
 *       completed bars (a brand-new signal, not a stale trend).</li>
 *   <li><b>Travel since the flip</b> — price has moved at least {@code minTravelAtr}×ATR
 *       in the trend direction, measured from the close at the flip bar to the current
 *       close. This is the "near-vertical" expansion right off the flip.</li>
 *   <li><b>Separation from the line</b> — {@code |close − supertrend| / ATR} is at least
 *       {@code minSeparationAtr} <em>and</em> wider than it was at the flip (price is
 *       pulling away from its own trailing band, not hugging it).</li>
 *   <li><b>Candle strength</b> — at least {@code minStrongCandles} of the last
 *       {@code strongCandleWindow} candles are strong directional bodies
 *       (body ≥ {@code bodyMinAtr}×ATR, closing in the trend direction).</li>
 *   <li><b>Whipsaw guard</b> — reject if there were ≥ {@code maxFlipsInWindow} direction
 *       flips within the last {@code flipWindowBars} bars (choppy market).</li>
 * </ol>
 *
 * <p>Pure Supertrend + momentum — no EMA involvement. Arithmetic uses
 * {@link MathContext#DECIMAL128}.</p>
 */
public final class SwiftTrendDetector {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int METRIC_SCALE = 4;

    private SwiftTrendDetector() {
        // utility class
    }

    /**
     * Tunable thresholds for swift-trend detection. All ATR-relative so the same numbers
     * work across differently-priced symbols.
     *
     * @param flipWithinBars    RESERVED — the flip is no longer required to be "fresh" (a
     *                          swift move may develop gradually over many bars). Kept for
     *                          config compatibility; not currently used as a gate.
     * @param minTravelAtr      min price travel since the flip, in ATR units (e.g. 2.5)
     * @param minSeparationAtr  min current |close−supertrend|/ATR (floor only; NOT required
     *                          to widen every bar — stair-step trends dip and recover) (e.g. 1.5)
     * @param strongCandleWindow window of recent candles inspected for strength (e.g. 5)
     * @param minStrongCandles  how many of that window must be strong directional bodies (e.g. 3)
     * @param bodyMinAtr        min candle body (|close−open|) as a fraction of ATR to count as strong (e.g. 0.5)
     * @param flipWindowBars    window used by the whipsaw guard (e.g. 5)
     * @param maxFlipsInWindow  reject when flips within {@code flipWindowBars} reach this count (e.g. 2)
     * @param stSlopeLookback   bars back over which the Supertrend LINE's own movement is
     *                          measured (e.g. 3). Confirms the line is actively trailing in
     *                          the trend direction, not shelved flat.
     * @param minStSlopeAtr     min Supertrend-line advance in the trend direction over
     *                          {@code stSlopeLookback} bars, in ATR units (e.g. 0.1). The line
     *                          must have risen (UP) / fallen (DOWN) by at least this; a flat or
     *                          wrong-way line is rejected. 0 = only require "not moving against".
     */
    public record Thresholds(
            int flipWithinBars,
            BigDecimal minTravelAtr,
            BigDecimal minSeparationAtr,
            int strongCandleWindow,
            int minStrongCandles,
            BigDecimal bodyMinAtr,
            int flipWindowBars,
            int maxFlipsInWindow,
            int stSlopeLookback,
            BigDecimal minStSlopeAtr
    ) {
        /** Sensible defaults matching the strategy discussed (ATR-normalised). */
        public static Thresholds defaults() {
            return new Thresholds(
                    5,
                    BigDecimal.valueOf(2.5),
                    BigDecimal.valueOf(1.5),
                    5,
                    3,
                    BigDecimal.valueOf(0.5),
                    5,
                    2,
                    3,
                    BigDecimal.valueOf(0.1));
        }
    }

    /**
     * Outcome of a swift-trend evaluation.
     *
     * @param swift       true when all checks passed (a swift UP or DOWN move)
     * @param direction   the trend direction (UP/DOWN) of the move, or null when not swift/unavailable
     * @param flipBarsAgo how many completed bars ago the flip occurred (−1 if n/a)
     * @param flipTime    the timestamp of the flip bar that STARTED the current trend leg
     *                    (null if n/a). Uniquely identifies the leg, so callers can latch
     *                    "one alert per leg" — a later signal in the same leg has the same
     *                    flipTime; a reversal (new flip) has a different one.
     * @param barTime     the timestamp of the evaluated (latest completed) bar (null if n/a).
     *                    Used by callers to gate alerts by session (e.g. RTH-only).
     * @param travelAtr   price travel since the flip in ATR units
     * @param separationAtr current |close−supertrend|/ATR
     * @param strongCandles count of strong directional candles in the window
     * @param flipsInWindow number of flips within the whipsaw window
     * @param close       the close of the evaluated (latest completed) bar
     * @param supertrend  the Supertrend line value at the evaluated bar
     * @param reason      short human-readable explanation (why swift / why not)
     */
    public record Result(
            boolean swift,
            Direction direction,
            int flipBarsAgo,
            java.time.Instant flipTime,
            java.time.Instant barTime,
            BigDecimal travelAtr,
            BigDecimal separationAtr,
            int strongCandles,
            int flipsInWindow,
            BigDecimal close,
            BigDecimal supertrend,
            String reason
    ) {
        static Result notSwift(String reason) {
            return new Result(false, null, -1, null, null, null, null, 0, 0, null, null, reason);
        }
    }

    /**
     * Evaluates the swift-trend condition on the latest <em>completed</em> bar of
     * {@code candles} using {@code defaults()} thresholds.
     *
     * @param candles OHLC candles oldest-first (may include the current in-progress bar)
     * @param length  Supertrend ATR period
     * @param factor  Supertrend ATR multiplier
     */
    public static Result evaluate(List<Candle> candles, int length, BigDecimal factor) {
        return evaluate(candles, length, factor, Thresholds.defaults());
    }

    /**
     * Evaluates the swift-trend condition on the latest <em>completed</em> bar of
     * {@code candles} (drops the last, possibly in-progress, candle).
     *
     * @param candles OHLC candles oldest-first (may include the current in-progress bar)
     * @param length  Supertrend ATR period
     * @param factor  Supertrend ATR multiplier
     * @param t       detection thresholds
     * @return a {@link Result}; {@code swift==false} with a reason when any check fails
     */
    public static Result evaluate(List<Candle> candles, int length, BigDecimal factor, Thresholds t) {
        if (candles == null || candles.size() < length + 3) {
            return Result.notSwift("insufficient bars");
        }
        // Work on COMPLETED bars only — drop the last, possibly in-progress, candle.
        List<Candle> completed = candles.subList(0, candles.size() - 1);

        List<Point> pts = SupertrendCalculator.calculate(completed, length, factor);

        // Find the latest point that actually has a computed Supertrend (skip warm-up).
        int cur = -1;
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).supertrend() != null) { cur = i; break; }
        }
        if (cur < 0) return Result.notSwift("supertrend warming up");

        Point curPt = pts.get(cur);
        Direction dir = curPt.direction();
        if (dir == null) return Result.notSwift("no direction");

        BigDecimal atr = curPt.atr();
        if (atr == null || atr.signum() <= 0) return Result.notSwift("atr unavailable");

        BigDecimal close = curPt.candle().close();
        BigDecimal st = curPt.supertrend();
        java.time.Instant barTime = curPt.candle().timestamp();

        // 1. Current trend's flip: the most recent flip bar at/before `cur`. The flip may be
        //    ANY age — a swift move can develop gradually over many bars (a multi-hour grind
        //    or a stair-step cascade), so we do NOT require the flip to be "fresh". We only
        //    require the latest flip to be in the CURRENT direction (i.e. we're in one trend
        //    leg, not past an opposite flip).
        int flipIdx = -1;
        for (int i = cur; i >= 0; i--) {
            if (pts.get(i).flip()) { flipIdx = i; break; }
        }
        if (flipIdx < 0) {
            return Result.notSwift("no flip in series");
        }
        int flipBarsAgo = cur - flipIdx;
        if (pts.get(flipIdx).direction() != dir) {
            return Result.notSwift("latest flip not in current direction");
        }
        // Timestamp of the flip bar — uniquely identifies this trend leg for one-alert-per-leg
        // latching by callers.
        java.time.Instant flipTime = pts.get(flipIdx).candle().timestamp();

        // 2. Whipsaw guard: too many flips in the recent window → choppy, reject.
        int flipsInWindow = 0;
        int wStart = Math.max(0, cur - t.flipWindowBars() + 1);
        for (int i = wStart; i <= cur; i++) {
            if (pts.get(i).flip()) flipsInWindow++;
        }
        if (flipsInWindow >= t.maxFlipsInWindow()) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, null, null, 0, flipsInWindow,
                    close, st, "whipsaw: " + flipsInWindow + " flips in " + t.flipWindowBars() + " bars");
        }

        // 3. Travel since the flip, in ATR units (signed by direction).
        BigDecimal travelAtr = travelAtrSinceFlip(pts, cur, flipIdx, dir, atr);
        if (travelAtr.compareTo(t.minTravelAtr()) < 0) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, null, 0, flipsInWindow,
                    close, st, "travel " + travelAtr + "×ATR < " + t.minTravelAtr());
        }

        // 4. Separation from the Supertrend line now — must clear the floor. (We DON'T require
        //    it to widen every bar: a stair-step Supertrend dips separation on bars where the
        //    line steps toward price, even while a strong trend continues. The travel check
        //    already proves the move's magnitude.)
        BigDecimal sepNow = separationAtr(close, st, atr);
        if (sepNow == null) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, null, 0, flipsInWindow,
                    close, st, "separation unavailable");
        }
        if (sepNow.compareTo(t.minSeparationAtr()) < 0) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, sepNow, 0, flipsInWindow,
                    close, st, "separation " + sepNow + "×ATR < " + t.minSeparationAtr());
        }

        // 4b. Supertrend LINE must be advancing in the trend direction (not shelved flat or
        //     moving against). Measure the line's own move over `stSlopeLookback` bars: UP
        //     requires the line to have RISEN by >= minStSlopeAtr×ATR, DOWN to have FALLEN by
        //     that much. The lookback is clamped to the flip bar / warm-up so it never crosses
        //     into the prior leg or null Supertrend.
        BigDecimal stSlopeAtr = stSlopeAtrOverLookback(pts, cur, flipIdx, dir, atr, t.stSlopeLookback());
        if (stSlopeAtr == null) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, sepNow, 0, flipsInWindow,
                    close, st, "supertrend slope unavailable");
        }
        if (stSlopeAtr.compareTo(t.minStSlopeAtr()) < 0) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, sepNow, 0, flipsInWindow,
                    close, st, "supertrend not advancing (" + stSlopeAtr + "×ATR < " + t.minStSlopeAtr() + ")");
        }

        // 5. Candle strength over the recent window: strong directional bodies.
        int strong = countStrongCandles(pts, cur, dir, atr, t.strongCandleWindow(), t.bodyMinAtr());
        if (strong < t.minStrongCandles()) {
            return new Result(false, dir, flipBarsAgo, flipTime, barTime, travelAtr, sepNow, strong, flipsInWindow,
                    close, st, "only " + strong + "/" + t.strongCandleWindow() + " strong candles");
        }

        // 6. The move "confirms" the first time travel crosses the threshold within this trend
        //    leg. We still report swift=true on EVERY qualifying bar of the leg here; the
        //    "fire once per leg" latch is applied by the caller (SwiftTrendScanner) using
        //    flipTime, which is exact and reversal-aware (a new flip = new leg = fires again).
        //    This fires right when the move becomes swift, whether 2 or 20 bars after the flip.

        // All checks passed → swift move.
        String arrow = dir == Direction.UP ? "UP" : "DOWN";
        String reason = "swift " + arrow + ": flip " + flipBarsAgo + " bars ago, travel "
                + travelAtr + "×ATR, sep " + sepNow + "×ATR, "
                + strong + "/" + t.strongCandleWindow() + " strong candles";
        return new Result(true, dir, flipBarsAgo, flipTime, barTime, travelAtr, sepNow, strong, flipsInWindow,
                close, st, reason);
    }

    /**
     * Travel from the flip bar's close to bar {@code at}'s close, in ATR units, signed so that
     * a move in {@code dir} is positive. Uses the ATR supplied for bar {@code at}.
     */
    private static BigDecimal travelAtrSinceFlip(List<Point> pts, int at, int flipIdx,
                                                 Direction dir, BigDecimal atr) {
        BigDecimal closeAtFlip = pts.get(flipIdx).candle().close();
        BigDecimal closeAt = pts.get(at).candle().close();
        BigDecimal rawTravel = (dir == Direction.UP)
                ? closeAt.subtract(closeAtFlip, MC)
                : closeAtFlip.subtract(closeAt, MC);
        return rawTravel.divide(atr, MC).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The Supertrend LINE's own movement in the trend direction over up to {@code lookback}
     * bars ending at {@code at}, in ATR units (signed so trend-direction movement is positive).
     * The start bar is clamped so it never goes before the flip bar or into a bar with no
     * computed Supertrend. Returns null when there's no valid prior ST value to compare.
     */
    private static BigDecimal stSlopeAtrOverLookback(List<Point> pts, int at, int flipIdx,
                                                     Direction dir, BigDecimal atr, int lookback) {
        if (atr == null || atr.signum() <= 0) return null;
        int start = Math.max(flipIdx, at - Math.max(1, lookback));
        // Walk forward from `start` to the first bar with a non-null Supertrend.
        while (start < at && pts.get(start).supertrend() == null) start++;
        if (start >= at) return null;
        BigDecimal stStart = pts.get(start).supertrend();
        BigDecimal stNow = pts.get(at).supertrend();
        if (stStart == null || stNow == null) return null;
        BigDecimal move = (dir == Direction.UP)
                ? stNow.subtract(stStart, MC)        // UP: line should rise
                : stStart.subtract(stNow, MC);       // DOWN: line should fall
        return move.divide(atr, MC).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code |close − supertrend| / atr}, or null when inputs are missing/invalid. */
    private static BigDecimal separationAtr(BigDecimal close, BigDecimal supertrend, BigDecimal atr) {
        if (close == null || supertrend == null || atr == null || atr.signum() <= 0) return null;
        return close.subtract(supertrend, MC).abs()
                .divide(atr, MC)
                .setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Counts how many of the last {@code window} candles (ending at {@code cur}) are strong
     * directional bodies: closing in {@code dir} with body {@code |close−open|} ≥
     * {@code bodyMinAtr}×ATR.
     */
    private static int countStrongCandles(List<Point> pts, int cur, Direction dir,
                                          BigDecimal atr, int window, BigDecimal bodyMinAtr) {
        BigDecimal minBody = atr.multiply(bodyMinAtr, MC);
        int start = Math.max(0, cur - window + 1);
        int strong = 0;
        for (int i = start; i <= cur; i++) {
            Candle c = pts.get(i).candle();
            boolean directional = (dir == Direction.UP)
                    ? c.close().compareTo(c.open()) > 0
                    : c.close().compareTo(c.open()) < 0;
            if (!directional) continue;
            BigDecimal body = c.close().subtract(c.open(), MC).abs();
            if (body.compareTo(minBody) >= 0) strong++;
        }
        return strong;
    }
}
