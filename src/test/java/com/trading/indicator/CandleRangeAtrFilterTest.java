package com.trading.indicator;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CandleRangeAtrFilterTest {
    private Candle candle(String high, String low) {
        return new Candle("TEST", Instant.parse("2026-10-16T13:30:00Z"), BigDecimal.TEN,
                new BigDecimal(high), new BigDecimal(low), BigDecimal.TEN, 100);
    }

    @Test
    void exactThresholdPassesAndLargerRangeIsRejected() {
        assertTrue(CandleRangeAtrFilter.evaluate(candle("13", "7"), new BigDecimal("2"), new BigDecimal("3")).passes());
        var rejected = CandleRangeAtrFilter.evaluate(candle("14", "6"), new BigDecimal("2"), new BigDecimal("3"));
        assertFalse(rejected.passes());
        assertEquals(new BigDecimal("4.0000"), rejected.rangeAtr());
        assertEquals("climax bar: range 4.0000×ATR > 3", rejected.reason());
    }

    @Test
    void disabledThresholdNeedsNoAtr() {
        assertTrue(CandleRangeAtrFilter.evaluate(null, null, null).passes());
        assertTrue(CandleRangeAtrFilter.evaluate(null, null, BigDecimal.ZERO).passes());
        assertTrue(CandleRangeAtrFilter.evaluate(null, null, BigDecimal.ONE.negate()).passes());
    }

    @Test
    void enabledFilterRejectsUnavailableAtr() {
        assertFalse(CandleRangeAtrFilter.evaluate(candle("14", "6"), null, BigDecimal.TEN).passes());
        assertFalse(CandleRangeAtrFilter.evaluate(candle("14", "6"), BigDecimal.ZERO, BigDecimal.TEN).passes());
    }

    @Test
    void preservesSwiftsFourDecimalComparison() {
        var result = CandleRangeAtrFilter.evaluate(candle("11", "9"), new BigDecimal("3"), new BigDecimal("0.6667"));
        assertEquals(new BigDecimal("0.6667"), result.rangeAtr());
        assertTrue(result.passes());
    }
}
