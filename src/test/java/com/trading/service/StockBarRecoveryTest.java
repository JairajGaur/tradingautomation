package com.trading.service;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBarRecoveryTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final MarketDataService market = mock(MarketDataService.class);
    private Candle candle(int ageSeconds) {
        return new Candle("AAPL", Instant.now().minusSeconds(ageSeconds),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100);
    }

    @Test
    void nativeRecoveryInvalidatesOnlyTargetCacheAndLimitsRetries() {
        when(props.engine().warmupBars()).thenReturn(100);
        var old = List.of(candle(3600));
        var fresh = List.of(candle(60));
        when(market.fetchHistoricalBars("AAPL", 700, "M15", null)).thenReturn(old).thenReturn(fresh);
        var manager = new BarDataManager(market, props);
        assertEquals(old, manager.getBars("AAPL", "M15", 20));
        assertEquals(fresh, manager.refreshBars("AAPL", "M15", 20));
        assertEquals(fresh, manager.refreshBars("AAPL", "M15", 20));
        verify(market, times(2)).fetchHistoricalBars("AAPL", 700, "M15", null);
    }

    @Test
    void emptyRecoveryRetainsPreviousHistoryForDiagnostics() {
        when(props.engine().warmupBars()).thenReturn(100);
        var old = List.of(candle(3600));
        when(market.fetchHistoricalBars("AAPL", 700, "M15", null)).thenReturn(old).thenReturn(List.of());
        var manager = new BarDataManager(market, props);
        manager.getBars("AAPL", "M15", 20);
        assertEquals(old, manager.refreshBars("AAPL", "M15", 20));
        assertEquals(old, manager.getBars("AAPL", "M15", 20));
    }
}
