package com.trading.service;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BarQualityTest {
    private final Instant start = Instant.parse("2026-10-16T13:30:00Z");
    private final BarQuality.Policy policy = new BarQuality.Policy(1, 0, 20, 1);
    private Candle bar(Instant time) {
        return new Candle("TEST", time, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100);
    }
    private List<Candle> bars(int count) {
        var list = new ArrayList<Candle>();
        for (int i = 0; i < count; i++) list.add(bar(start.plusSeconds(i * 60L)));
        return list;
    }
    private BarQuality.Result assess(List<Candle> list, Instant time, int minimum) {
        return BarQuality.assess(list, 1, time, minimum, policy);
    }

    @Test
    void closedOnlyAndIncludingFormingProduceIdenticalCompletedData() {
        var closed = assess(bars(20), start.plusSeconds(1200), 20);
        var withForming = assess(bars(21), start.plusSeconds(1200), 20);
        assertTrue(closed.valid());
        assertEquals(closed, withForming);
        assertEquals(20, closed.completed().size());
    }

    @Test
    void exactCloseBoundaryAndFutureBarsAreCausal() {
        assertEquals(0, assess(bars(10), start.plusSeconds(59), 1).completed().size());
        assertEquals(1, assess(bars(10), start.plusSeconds(60), 1).completed().size());
        var formingInvalid = new Candle("TEST", start.plusSeconds(60), null, null, null, BigDecimal.ONE, -1);
        assertTrue(assess(List.of(bar(start), formingInvalid), start.plusSeconds(60), 1).valid());
    }

    @Test
    void freshnessAndActualWarmupAreEnforced() {
        assertEquals("INSUFFICIENT_WARMUP", assess(bars(3), start.plusSeconds(180), 20).reason());
        assertEquals("STALE_BARS", assess(bars(20), start.plusSeconds(1260), 20).reason());
        assertEquals("STALE_BARS", assess(bars(20), start.plusSeconds(86400), 20).reason());
    }

    @Test
    void repairsOrderAndIdenticalDuplicatesButRejectsConflicts() {
        var input = new ArrayList<>(bars(20));
        input.add(input.get(5));
        Collections.reverse(input);
        assertEquals(bars(20), assess(input, start.plusSeconds(1200), 20).completed());
        input.add(new Candle("TEST", start, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, 999));
        assertEquals("CONFLICTING_DUPLICATE", assess(input, start.plusSeconds(1200), 20).reason());
    }

    @Test
    void gapsAreBoundedWithoutCountingOvernightClosure() {
        var input = new ArrayList<>(bars(20));
        input.remove(10);
        assertTrue(assess(input, start.plusSeconds(1200), 10).valid());
        input.remove(10);
        assertEquals("EXCESSIVE_GAPS", assess(input, start.plusSeconds(1200), 10).reason());
        input = new ArrayList<>(bars(20));
        input.add(0, bar(Instant.parse("2026-10-15T19:59:00Z")));
        assertTrue(assess(input, start.plusSeconds(1200), 20).valid());
    }

    @Test
    void invalidOhlcvAndMisalignedTimesAreRejected() {
        var invalid = new Candle("TEST", start, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.TEN, 100);
        assertEquals("INVALID_OHLCV", assess(List.of(invalid), start.plusSeconds(60), 1).reason());
        assertEquals("MISALIGNED_BARS", assess(List.of(bar(start.plusSeconds(1))), start.plusSeconds(90), 1).reason());
    }
}
