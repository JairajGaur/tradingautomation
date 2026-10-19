package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionVolumeStAlertTest {
    @Test
    void liveLoadingSelectsOnlyHaSideAndSkipsUnavailableDirection() {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.alerts().optionVolSt().timeframe()).thenReturn("M1");
        when(props.alerts().optionVolSt().haDirectionEnabled()).thenReturn(true);
        var universe = mock(OptionUniverseLoader.class);
        when(universe.getUnderlyings()).thenReturn(List.of("AAPL"));
        var contracts = mock(OptionContractService.class);
        var filter = mock(OptionHaDirectionFilter.class);
        var series = mock(OptionHaDirectionFilter.Series.class);
        when(filter.load("AAPL")).thenReturn(series);
        var alert = new OptionVolumeStAlert(props, universe, contracts, null, null, filter);
        for (var side : OptionContract.Type.values()) {
            when(filter.current(eq("AAPL"), any())).thenReturn(new OptionHaDirectionFilter.Direction(
                    side == OptionContract.Type.CALL ? "GREEN" : "RED", null, side));
            alert.evaluate(List.of());
            verify(contracts).loadUnderlying("AAPL", java.util.EnumSet.of(side));
        }
        clearInvocations(contracts);
        when(filter.current(eq("AAPL"), any())).thenReturn(new OptionHaDirectionFilter.Direction("DOJI", null, null));
        alert.evaluate(List.of());
        verifyNoInteractions(contracts);
    }

    private OptionVolumeStAlert alert(String sourceTimeframe) {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.alerts().optionVolSt().timeframe()).thenReturn(sourceTimeframe);
        return new OptionVolumeStAlert(props, null, null, null, null, null);
    }

    private boolean gate(String source, String target, List<Candle> bars) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                alert(source), "threeMinStDirectionUp", bars.subList(0, Math.max(0, bars.size() - 1)), target,
                bars.isEmpty() ? Instant.parse("2026-10-16T13:30:00Z") : bars.get(bars.size() - 1).timestamp()));
    }

    private List<Candle> risingBars(int count) {
        List<Candle> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-10-16T13:30:00Z");
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(10 + i);
            bars.add(new Candle("TEST", start.plusSeconds(60L * i), price,
                    price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price, 100));
        }
        return bars;
    }

    @Test
    void m3NeedsEnoughAggregatedBarsToWarmUp() {
        // M1 has already flipped UP while M3 is still at its initial DOWN seed.
        assertTrue(gate("M1", "M1", risingBars(12)));
        assertFalse(gate("M1", "M3", risingBars(12)));
        assertFalse(gate("M1", "M3", risingBars(6)));
        assertTrue(gate("M1", "M3", risingBars(60)));
    }

    @Test
    void formingM3CandleCannotChangeCompletedDirection() {
        List<Candle> bars = risingBars(61);
        assertTrue(gate("M1", "M3", bars));
        Candle forming = bars.get(60);
        bars.set(60, new Candle("TEST", forming.timestamp(), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 100));
        assertTrue(gate("M1", "M3", bars));
    }

    @Test
    void unavailableOrIncompatibleTimeframesFailClosed() {
        assertFalse(gate("M1", "M3", List.of()));
        assertFalse(gate("M1", "D", risingBars(60)));
        assertFalse(gate("M5", "M3", risingBars(60)));
        assertFalse(gate("M5", "M7", risingBars(60)));
        assertFalse(gate("M1", "invalid", risingBars(60)));
    }
}
