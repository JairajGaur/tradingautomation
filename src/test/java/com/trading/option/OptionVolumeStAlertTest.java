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
    private OptionVolumeStAlert alert(String sourceTimeframe) {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.alerts().optionVolSt().timeframe()).thenReturn(sourceTimeframe);
        return new OptionVolumeStAlert(props, null, null, null, null);
    }

    private boolean gate(String source, String target, List<Candle> bars) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                alert(source), "threeMinStDirectionUp", bars, target));
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
