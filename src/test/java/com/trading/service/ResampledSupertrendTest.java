package com.trading.service;

import com.trading.indicator.SupertrendCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms the Supertrend values generated on RESAMPLED (synthetic M3) candles are well-formed
 * and internally consistent — i.e. the resampler produces OHLC bars the production
 * {@link SupertrendCalculator} can consume exactly like native bars.
 *
 * <p>Correctness checks on the M3 series:
 * <ul>
 *   <li>number of M3 bars = ceil(M1 count / 3);</li>
 *   <li>after the ATR warm-up ({@code length} bars) every bar has a non-null ATR + Supertrend
 *       + direction (no gaps);</li>
 *   <li>ATR is strictly positive;</li>
 *   <li>the Supertrend line sits on the correct side of price (below in UP, above in DOWN) —
 *       the defining property of the indicator;</li>
 *   <li>a {@code flip} only ever occurs where the direction actually changed.</li>
 * </ul>
 * No broker/network — a deterministic rising-then-falling M1 series is resampled in-memory.
 */
class ResampledSupertrendTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int LENGTH = 7;
    private static final BigDecimal FACTOR = BigDecimal.valueOf(3);

    private static Candle m1(int idxMinute, double open, double high, double low, double close) {
        // Start 2026-10-09 09:30 ET, one bar per minute.
        Instant ts = ZonedDateTime.of(2026, 10, 9, 9, 30, 0, 0, ET).plusMinutes(idxMinute).toInstant();
        return new Candle("TEST", ts, bd(open), bd(high), bd(low), bd(close), 1000);
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    /** 180 M1 bars: a steady rise for the first half, a steady fall for the second. */
    private static List<Candle> syntheticM1() {
        List<Candle> c = new ArrayList<>();
        double p = 100.0;
        for (int i = 0; i < 90; i++) {           // rising leg
            double open = p, close = p + 0.15;
            c.add(m1(i, open, close + 0.05, open - 0.05, close));
            p = close;
        }
        for (int i = 90; i < 180; i++) {         // falling leg
            double open = p, close = p - 0.15;
            c.add(m1(i, open, open + 0.05, close - 0.05, close));
            p = close;
        }
        return c;
    }

    @Test
    void supertrendOnResampledM3IsWellFormedAndConsistent() {
        List<Candle> m1 = syntheticM1();
        List<Candle> m3 = BarResampler.resample(m1, 3);

        assertEquals((int) Math.ceil(m1.size() / 3.0), m3.size(), "180 M1 → 60 M3 bars");

        List<Point> pts = SupertrendCalculator.calculate(m3, LENGTH, FACTOR);
        assertEquals(m3.size(), pts.size(), "one ST point per M3 bar");

        boolean sawUp = false, sawDown = false, sawFlip = false;
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            if (i < LENGTH) continue;   // warm-up: atr/line/direction may be null

            assertNotNull(pt.atr(), "ATR present after warm-up at bar " + i);
            assertTrue(pt.atr().signum() > 0, "ATR strictly positive at bar " + i);
            assertNotNull(pt.supertrend(), "Supertrend present after warm-up at bar " + i);
            assertNotNull(pt.direction(), "direction present after warm-up at bar " + i);

            // The defining property: line below close in UP, above close in DOWN.
            BigDecimal close = pt.candle().close();
            if (pt.direction() == Direction.UP) {
                sawUp = true;
                assertTrue(pt.supertrend().compareTo(close) <= 0,
                        "UP: Supertrend line must be <= close at bar " + i
                                + " (ST=" + pt.supertrend() + ", close=" + close + ")");
            } else {
                sawDown = true;
                assertTrue(pt.supertrend().compareTo(close) >= 0,
                        "DOWN: Supertrend line must be >= close at bar " + i
                                + " (ST=" + pt.supertrend() + ", close=" + close + ")");
            }

            // A flip only where direction actually changed vs the previous computed bar.
            if (pt.flip() && pts.get(i - 1).direction() != null) {
                sawFlip = true;
                assertNotEquals(pts.get(i - 1).direction(), pt.direction(),
                        "flip at bar " + i + " must coincide with a direction change");
            }
        }

        // The rise-then-fall series must exercise both directions and at least one flip.
        assertTrue(sawUp, "series should spend time in an UP leg");
        assertTrue(sawDown, "series should spend time in a DOWN leg");
        assertTrue(sawFlip, "the rise→fall transition should produce a Supertrend flip");
    }

    @Test
    void resampledHighLowBoundTheConstituentM1Bars() {
        // Independent sanity: each M3 bar's high/low must bound its 3 source M1 bars' extremes.
        List<Candle> m1 = syntheticM1();
        List<Candle> m3 = BarResampler.resample(m1, 3);

        for (int b = 0; b < m3.size(); b++) {
            Candle agg = m3.get(b);
            BigDecimal hi = null, lo = null;
            for (int k = b * 3; k < Math.min(b * 3 + 3, m1.size()); k++) {
                Candle src = m1.get(k);
                hi = (hi == null) ? src.high() : hi.max(src.high());
                lo = (lo == null) ? src.low() : lo.min(src.low());
            }
            assertEquals(hi, agg.high(), "M3 high = max of constituent M1 highs (bar " + b + ")");
            assertEquals(lo, agg.low(), "M3 low = min of constituent M1 lows (bar " + b + ")");
        }
    }
}
