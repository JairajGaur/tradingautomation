package com.trading.service;

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
 * Verifies {@link BarResampler} aggregates M1 bars into M2/M3 correctly (OHLCV rule,
 * hour-aligned bucketing, gap tolerance) and {@link TimeframeSupport} classifies minute
 * timeframes. No broker/network needed — pure in-memory synthetic bars.
 */
class BarResamplerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Candle m1(int hour, int minute, double open, double high, double low, double close, long vol) {
        Instant ts = ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, ET).toInstant();
        return new Candle("TEST", ts, bd(open), bd(high), bd(low), bd(close), vol);
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private static int etMinute(Candle c) { return c.timestamp().atZone(ET).getMinute(); }
    private static int etHour(Candle c)   { return c.timestamp().atZone(ET).getHour(); }

    @Test
    void m2_aggregatesPairsOfM1_withCorrectOhlcv() {
        List<Candle> m1 = List.of(
                m1(9, 30, 100, 102,  99, 101, 10),
                m1(9, 31, 101, 105, 100, 104, 20),   // bucket 09:30–09:32
                m1(9, 32, 104, 106, 103, 103, 30),
                m1(9, 33, 103, 104, 101, 102, 40));  // bucket 09:32–09:34

        List<Candle> m2 = BarResampler.resample(m1, 2);

        assertEquals(2, m2.size(), "4 M1 bars -> 2 M2 bars");

        Candle b0 = m2.get(0);
        assertEquals(9, etHour(b0));
        assertEquals(30, etMinute(b0), "first M2 bar starts at 09:30");
        assertEquals(bd(100), b0.open(),  "open = first M1 open");
        assertEquals(bd(105), b0.high(),  "high = max over bucket");
        assertEquals(bd(99),  b0.low(),   "low = min over bucket");
        assertEquals(bd(104), b0.close(), "close = last M1 close");
        assertEquals(30, b0.volume(),     "volume = sum (10+20)");

        Candle b1 = m2.get(1);
        assertEquals(32, etMinute(b1), "second M2 bar starts at 09:32");
        assertEquals(bd(104), b1.open());
        assertEquals(bd(106), b1.high());
        assertEquals(bd(101), b1.low());
        assertEquals(bd(102), b1.close());
        assertEquals(70, b1.volume());
    }

    @Test
    void m3_bucketsAreHourAligned() {
        List<Candle> m1 = List.of(
                m1(9, 30, 10, 11, 9, 10, 1),
                m1(9, 31, 10, 12, 9, 11, 1),
                m1(9, 32, 11, 13, 10, 12, 1),
                m1(9, 33, 12, 14, 11, 13, 1));

        List<Candle> m3 = BarResampler.resample(m1, 3);

        assertEquals(2, m3.size());
        assertEquals(30, etMinute(m3.get(0)), "M3 bucket aligns to 09:30");
        assertEquals(bd(13), m3.get(0).high(), "high across 3 bars");
        assertEquals(3, m3.get(0).volume());
        assertEquals(33, etMinute(m3.get(1)), "next M3 bucket at 09:33");
        assertEquals(1, m3.get(1).volume(), "trailing partial bucket still emitted");
    }

    @Test
    void m3_toleratesMissingMinuteWithinBucket() {
        List<Candle> m1 = List.of(
                m1(9, 30, 10, 11, 9, 10, 5),
                m1(9, 32, 11, 15, 8, 14, 7));   // 09:31 missing

        List<Candle> m3 = BarResampler.resample(m1, 3);

        assertEquals(1, m3.size());
        Candle b = m3.get(0);
        assertEquals(30, etMinute(b));
        assertEquals(bd(10), b.open());
        assertEquals(bd(15), b.high());
        assertEquals(bd(8), b.low());
        assertEquals(bd(14), b.close());
        assertEquals(12, b.volume(), "sum of the present minutes only");
    }

    @Test
    void m1_returnsCopyUnchanged() {
        List<Candle> m1 = new ArrayList<>(List.of(m1(9, 30, 1, 1, 1, 1, 1)));
        List<Candle> out = BarResampler.resample(m1, 1);
        assertEquals(1, out.size());
        assertNotSame(m1, out, "defensive copy");
        assertEquals(m1.get(0), out.get(0));
    }

    @Test
    void emptyAndNullAreSafe() {
        assertTrue(BarResampler.resample(List.of(), 3).isEmpty());
        assertTrue(BarResampler.resample(null, 3).isEmpty());
    }

    @Test
    void rejectsZeroOrNegativeMinutes() {
        assertThrows(IllegalArgumentException.class, () -> BarResampler.resample(List.of(), 0));
    }

    @Test
    void timeframeClassification() {
        assertEquals(2, TimeframeSupport.minutesOf("M2"));
        assertEquals(3, TimeframeSupport.minutesOf("m3"));
        assertEquals(5, TimeframeSupport.minutesOf("M5"));
        assertEquals(-1, TimeframeSupport.minutesOf("D"));
        assertEquals(-1, TimeframeSupport.minutesOf("M"));

        assertTrue(TimeframeSupport.isSynthetic("M2"));
        assertTrue(TimeframeSupport.isSynthetic("M3"));
        assertFalse(TimeframeSupport.isSynthetic("M5"), "M5 is native");
        assertFalse(TimeframeSupport.isSynthetic("M1"), "M1 is native");

        assertTrue(TimeframeSupport.isNativeMinute("M5"));
        assertTrue(TimeframeSupport.isNativeMinute("M1"));
        assertFalse(TimeframeSupport.isNativeMinute("M2"));
        assertFalse(TimeframeSupport.isNativeMinute("D"));
    }

    @Test
    void normaliseTimespanAcceptsSyntheticMinuteTimeframes() {
        assertEquals("M2", MarketDataService.normaliseTimespan("m2"));
        assertEquals("M3", MarketDataService.normaliseTimespan("M3"));
        assertEquals("M5", MarketDataService.normaliseTimespan("M5"));
        assertThrows(IllegalArgumentException.class, () -> MarketDataService.normaliseTimespan("M0"));
        assertThrows(IllegalArgumentException.class, () -> MarketDataService.normaliseTimespan("XYZ"));
    }
}
