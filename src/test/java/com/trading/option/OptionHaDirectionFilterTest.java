package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionHaDirectionFilterTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final BarDataManager bars = mock(BarDataManager.class);
    private final Instant start = Instant.parse("2026-10-16T13:30:00Z");

    private Candle candle(Instant time, int open, int high, int low, int close) {
        return new Candle("AAPL", time, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 100);
    }

    private OptionHaDirectionFilter filter(List<Candle> source) {
        when(props.alerts().optionVolSt().haTimeframe()).thenReturn("M15");
        when(props.engine().warmupBars()).thenReturn(700);
        when(bars.getBars("AAPL", "M15", 700)).thenReturn(source);
        return new OptionHaDirectionFilter(props, bars);
    }

    @Test
    void greenUnderlyingAllowsOnlyCallsAfterCandleCloses() {
        var series = filter(List.of(candle(start, 10, 16, 10, 12))).load("AAPL");
        assertEquals("UNAVAILABLE", series.at(start.plusSeconds(899)).color());
        var direction = series.at(start.plusSeconds(900));
        assertEquals("GREEN", direction.color());
        assertTrue(direction.allows(OptionContract.Type.CALL));
        assertFalse(direction.allows(OptionContract.Type.PUT));
    }

    @Test
    void redUnderlyingAllowsOnlyPuts() {
        var direction = filter(List.of(candle(start, 12, 12, 6, 10)))
                .load("AAPL").at(start.plusSeconds(900));
        assertEquals("RED", direction.color());
        assertTrue(direction.allows(OptionContract.Type.PUT));
        assertFalse(direction.allows(OptionContract.Type.CALL));
    }

    @Test
    void dojiAndStaleDataBlockBothSides() {
        var series = filter(List.of(candle(start, 10, 10, 10, 10))).load("AAPL");
        var doji = series.at(start.plusSeconds(900));
        assertEquals("DOJI", doji.color());
        assertNull(doji.allowedSide());
        assertEquals("UNAVAILABLE", series.at(start.plusSeconds(1800)).color());
        assertNull(series.at(start.plusSeconds(86400)).allowedSide());
        assertNull(filter(List.of()).load("AAPL").at(start).allowedSide());
    }

    @Test
    void replayDoesNotReadFormingOrFutureCandleColor() {
        var first = candle(start, 10, 16, 10, 12);
        var redFuture = candle(start.plusSeconds(900), 5, 5, 1, 1);
        var series = filter(List.of(first, redFuture)).load("AAPL");
        assertEquals("GREEN", series.at(start.plusSeconds(1799)).color());
        assertEquals("RED", series.at(start.plusSeconds(1800)).color());
    }

    @Test
    void liveHaRecoversStaleHistoryAndRequiresConfiguredWarmup() {
        var filter = filter(List.of(candle(start.minusSeconds(86400), 10, 16, 10, 12)));
        when(props.alerts().optionVolSt().barQuality().haWarmupBars()).thenReturn(20);
        var source = new java.util.ArrayList<Candle>();
        for (int i = 0; i < 25; i++) source.add(candle(start.plusSeconds(i * 900L), 10, 16, 10, 12));
        when(bars.refreshBars("AAPL", "M15", 1650)).thenReturn(source);
        assertEquals("GREEN", filter.current("AAPL", start.plusSeconds(25 * 900L)).color());
        verify(bars).refreshBars("AAPL", "M15", 1650);
        assertEquals("UNAVAILABLE", filter.load("AAPL").at(start.plusSeconds(900)).color());
    }

    @Test
    void syntheticHaDoesNotAcceptIncompleteSourceBuckets() {
        var filter = filter(List.of());
        when(props.alerts().optionVolSt().haTimeframe()).thenReturn("M3");
        when(props.alerts().optionVolSt().barQuality().haWarmupBars()).thenReturn(3);
        var source = new java.util.ArrayList<Candle>();
        for (int i = 0; i < 30; i++) source.add(candle(start.plusSeconds(i * 60L), 10, 16, 10, 12));
        when(bars.getBars("AAPL", "M1", 1650)).thenReturn(source);
        assertEquals("GREEN", filter.load("AAPL").at(start.plusSeconds(1800)).color());
        source.remove(26); // breaks a recently completed M3 bucket
        assertEquals("UNAVAILABLE", filter.load("AAPL").at(start.plusSeconds(1800)).color());
    }

    @Test
    void configurableTimeframeAndInvalidDataFailClosed() {
        var filter = filter(List.of(candle(start, 10, 16, 10, 12)));
        when(props.alerts().optionVolSt().haTimeframe()).thenReturn("M5");
        when(bars.getBars("AAPL", "M5", 700)).thenReturn(List.of(candle(start, 10, 16, 10, 12)));
        assertEquals("GREEN", filter.load("AAPL").at(start.plusSeconds(300)).color());
        when(props.alerts().optionVolSt().haTimeframe()).thenReturn("invalid");
        assertEquals("UNAVAILABLE", filter.load("AAPL").at(start).color());
    }
}
