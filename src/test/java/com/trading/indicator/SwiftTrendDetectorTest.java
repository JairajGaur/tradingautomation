package com.trading.indicator;

import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SwiftTrendDetector.Result;
import com.trading.indicator.SwiftTrendDetector.Thresholds;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for {@link SwiftTrendDetector} — the shared detection used by both the
 * live scanner and the backtest. Verifies it:
 * <ul>
 *   <li>fires on a sustained move (not just near-vertical ones),</li>
 *   <li>fires ONCE per move (only on the confirmation bar),</li>
 *   <li>stays silent on chop.</li>
 * </ul>
 *
 * <p>Uses length=7, factor=3 (the app defaults) and the default thresholds.</p>
 */
class SwiftTrendDetectorTest {

    private static final int LENGTH = 7;
    private static final BigDecimal FACTOR = BigDecimal.valueOf(3);
    private static final Thresholds T = Thresholds.defaults();

    /** A candle; green when close>open. open/high/low derived to give a real body + range. */
    private static Candle bar(int i, double open, double high, double low, double close) {
        return new Candle("TEST", Instant.ofEpochSecond(i * 300L),  // 5-min bars
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1000L);
    }

    /** Evaluate the detector on the series through index {@code end} (inclusive). */
    private static Result at(List<Candle> all, int end) {
        // The detector drops the last bar as in-progress; pass through end+1 so `end` is
        // the evaluated completed bar. Duplicate the last if end is the final index.
        List<Candle> window = new ArrayList<>(all.subList(0, Math.min(end + 2, all.size())));
        if (end + 1 >= all.size()) window.add(all.get(end));
        return SwiftTrendDetector.evaluate(window, LENGTH, FACTOR, T);
    }

    /** Builds: flat base (seeds a DOWN or neutral trend), then a strong sustained rise. */
    private static List<Candle> sustainedRiseSeries() {
        List<Candle> c = new ArrayList<>();
        int i = 0;
        // Firm declining base so the trend starts DOWN and the rise causes a real UP flip.
        double p = 120.0;
        for (; i < 15; i++) {
            double o = p, cl = p - 1.0;      // strong red base → trend DOWN
            c.add(bar(i, o + 0.1, o + 0.1, cl - 0.1, cl));
            p = cl;
        }
        // Strong sustained climb: big green bodies, each ~ +1.0, for 20 bars.
        for (int k = 0; k < 20; k++, i++) {
            double o = p, cl = p + 1.0;
            c.add(bar(i, o, cl + 0.1, o - 0.1, cl));   // strong green body
            p = cl;
        }
        return c;
    }

    /** Builds: a firm UP base (so Supertrend is UP), then a strong sustained decline that
     *  flips it DOWN. Mirror of the rise series. */
    private static List<Candle> sustainedFallSeries() {
        List<Candle> c = new ArrayList<>();
        int i = 0;
        double p = 80.0;
        // Firm rising base so the Supertrend is established UP before the drop.
        for (; i < 15; i++) {
            double o = p, cl = p + 1.0;      // strong green base → trend UP
            c.add(bar(i, o, cl + 0.1, o - 0.1, cl));
            p = cl;
        }
        // Strong sustained decline: big red bodies that flip Supertrend DOWN and run.
        for (int k = 0; k < 20; k++, i++) {
            double o = p, cl = p - 1.0;
            c.add(bar(i, o + 0.1, o + 0.1, cl - 0.1, cl));   // strong red body
            p = cl;
        }
        return c;
    }

    /** Builds a choppy series: small alternating bodies, no sustained travel. */
    private static List<Candle> chopSeries() {
        List<Candle> c = new ArrayList<>();
        double p = 100.0;
        for (int i = 0; i < 60; i++) {
            double delta = (i % 2 == 0) ? 0.15 : -0.15;      // tiny alternating moves
            double o = p, cl = p + delta;
            c.add(bar(i, o, Math.max(o, cl) + 0.1, Math.min(o, cl) - 0.1, cl));
            p = cl;
        }
        return c;
    }

    /**
     * Counts ACTUAL fires in a given direction across the whole replay — applying the shared
     * scanner's fire-once-per-leg latch + cooldown (via {@link SwiftTrendScanner#decide}),
     * exactly as the live scanner and backtest do. Uses each bar's index as the "time" so the
     * cooldown (30 real minutes) never interferes; the per-leg latch is what's under test.
     */
    private static int countFires(List<Candle> series, Direction want) {
        java.util.Map<String, SwiftTrendScanner.FireState> state = new java.util.HashMap<>();
        int fires = 0;
        for (int end = LENGTH + 2; end < series.size(); end++) {
            Result r = at(series, end);
            // Use a monotonically increasing ms far apart so the 30-min cooldown is a non-factor;
            // the per-leg latch (flipTime) is what we're asserting.
            long atMs = (long) end * 60 * 60 * 1000L;   // 1 hour per bar
            // RTH gate forced true here — this test exercises the per-leg latch, not sessions.
            SwiftTrendScanner.Decision d = SwiftTrendScanner.decide("TEST", r, atMs, 30, true, state);
            if (d.fire() && r.direction() == want) fires++;
        }
        return fires;
    }

    @Test
    void firesOnceOnSustainedRise() {
        // The rise leg should fire exactly one swift UP (on its confirmation bar), even
        // though the move develops over many bars.
        assertEquals(1, countFires(sustainedRiseSeries(), Direction.UP),
                "a sustained rise should fire exactly one UP (confirmation bar)");
    }

    @Test
    void firesOnceOnSustainedFall() {
        // The decline leg should fire exactly one swift DOWN. (The firm UP base used to set
        // up the DOWN flip is itself an up move and may fire its own UP — we assert on the
        // DOWN leg specifically.)
        assertEquals(1, countFires(sustainedFallSeries(), Direction.DOWN),
                "a sustained fall should fire exactly one DOWN (confirmation bar)");
    }

    @Test
    void stSlopeGateRejectsWhenLineNotAdvancingEnough() {
        // Same sustained rise that fires with default thresholds, but demand an impossibly
        // large Supertrend-line advance (100x ATR over the lookback). The ST-slope gate must
        // now reject every bar — proving the gate is wired and active.
        Thresholds strict = new Thresholds(
                T.flipWithinBars(), T.minTravelAtr(), T.minSeparationAtr(),
                T.strongCandleWindow(), T.minStrongCandles(), T.bodyMinAtr(),
                T.flipWindowBars(), T.maxFlipsInWindow(),
                T.stSlopeLookback(), BigDecimal.valueOf(100), T.maxCandleAtr());

        List<Candle> series = sustainedRiseSeries();
        int fires = 0;
        boolean sawSlopeReject = false;
        for (int end = LENGTH + 2; end < series.size(); end++) {
            List<Candle> window = new ArrayList<>(series.subList(0, Math.min(end + 2, series.size())));
            if (end + 1 >= series.size()) window.add(series.get(end));
            Result r = SwiftTrendDetector.evaluate(window, LENGTH, FACTOR, strict);
            if (r.swift()) fires++;
            if (r.reason() != null && r.reason().startsWith("supertrend not advancing")) sawSlopeReject = true;
        }
        assertEquals(0, fires, "an impossibly strict ST-slope threshold must reject all bars");
        assertTrue(sawSlopeReject, "the ST-slope gate should be the rejecting check");
    }

    @Test
    void silentOnChop() {
        List<Candle> series = chopSeries();
        int fires = 0;
        for (int end = LENGTH + 2; end < series.size(); end++) {
            if (at(series, end).swift()) fires++;
        }
        assertEquals(0, fires, "chop must not fire any swift signal");
    }
}
