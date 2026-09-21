package com.trading.indicator;

import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Supertrend math with a small hand-checkable dataset.
 *
 * <p>Uses length=3, factor=1 so ATR and bands are easy to reason about.</p>
 */
class SupertrendCalculatorTest {

    private static Candle bar(int i, double high, double low, double close) {
        // open is irrelevant to Supertrend; reuse close.
        return new Candle("TEST", Instant.ofEpochSecond(i * 60L),
                BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 100L);
    }

    @Test
    void computesAtrWithWilderSmoothing() {
        // TR for each bar (length=3):
        //  b0: 10-8 = 2               (no prev close)
        //  b1: max(11-9, |11-9|, |9-9|)   = 2   (prevClose 9)
        //  b2: max(12-10,|12-10|,|10-10|) = 2   (prevClose 10)
        //  seed ATR at index 2 = (2+2+2)/3 = 2
        //  b3: TR = max(13-11,|13-11|,|11-11|)=2 ; ATR=(2*2 + 2)/3 = 2
        List<Candle> candles = new ArrayList<>();
        candles.add(bar(0, 10, 8, 9));
        candles.add(bar(1, 11, 9, 10));
        candles.add(bar(2, 12, 10, 11));
        candles.add(bar(3, 13, 11, 12));

        List<Point> pts = SupertrendCalculator.calculate(candles, 3, BigDecimal.ONE);

        // First two bars are warm-up (null).
        assertNull(pts.get(0).supertrend());
        assertNull(pts.get(1).supertrend());

        // Seed bar (index 2): ATR = 2.
        assertEquals(0, pts.get(2).atr().compareTo(BigDecimal.valueOf(2)),
                "seed ATR should be 2");
        // Index 3: ATR still 2 (all TRs are 2).
        assertEquals(0, pts.get(3).atr().compareTo(BigDecimal.valueOf(2)),
                "wilder ATR should be 2");
    }

    @Test
    void detectsUptrendAndSupertrendBelowPrice() {
        // Steadily rising series → should settle into an UP trend with the
        // Supertrend line (lower band) sitting below the close.
        List<Candle> candles = new ArrayList<>();
        double base = 100;
        for (int i = 0; i < 20; i++) {
            double c = base + i;                 // rising close
            candles.add(bar(i, c + 1, c - 1, c));
        }
        Point latest = SupertrendCalculator.latest(candles, 7, BigDecimal.valueOf(3));
        assertNotNull(latest);
        assertEquals(Direction.UP, latest.direction());
        assertTrue(latest.supertrend().compareTo(latest.candle().close()) < 0,
                "in an uptrend the supertrend line is below the close");
    }

    @Test
    void flipsFromUpToDownOnReversal() {
        List<Candle> candles = new ArrayList<>();
        // Rise for 15 bars then sharply fall for 10.
        for (int i = 0; i < 15; i++) {
            double c = 100 + i;
            candles.add(bar(i, c + 1, c - 1, c));
        }
        for (int i = 0; i < 10; i++) {
            double c = 114 - (i + 1) * 3;        // fast drop
            candles.add(bar(15 + i, c + 1, c - 1, c));
        }
        List<Point> pts = SupertrendCalculator.calculate(candles, 7, BigDecimal.valueOf(3));

        boolean sawFlipToDown = pts.stream()
                .anyMatch(p -> p.flip() && p.direction() == Direction.DOWN);
        assertTrue(sawFlipToDown, "a downward reversal should produce a flip to DOWN");

        Point latest = SupertrendCalculator.latest(candles, 7, BigDecimal.valueOf(3));
        assertEquals(Direction.DOWN, latest.direction());
        assertTrue(latest.supertrend().compareTo(latest.candle().close()) > 0,
                "in a downtrend the supertrend line is above the close");
    }
}
