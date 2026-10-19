package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionBarRecoveryTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final MarketDataService market = mock(MarketDataService.class);
    private List<Candle> history(int ageSeconds) {
        return List.of(new Candle("TEST", Instant.now().minusSeconds(ageSeconds),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100));
    }

    @Test
    void recoveryBypassesCurrentCacheAndIsBoundedPerMinute() {
        var manager = new OptionBarDataManager(market, props);
        var old = history(3600);
        var fresh = history(60);
        when(market.fetchOptionBars("TEST", 200, "M1")).thenReturn(old).thenReturn(fresh);
        assertEquals(old, manager.getBars("TEST", "M1", 10));
        assertEquals(fresh, manager.refreshBars("TEST", "M1", 10));
        assertEquals(fresh, manager.refreshBars("TEST", "M1", 10));
        verify(market, times(2)).fetchOptionBars("TEST", 200, "M1");
    }

    @Test
    void failedRecoveryRetainsHistoryForDiagnosticsWithoutRetryLoop() {
        var manager = new OptionBarDataManager(market, props);
        var old = history(3600);
        when(market.fetchOptionBars("TEST", 200, "M1")).thenReturn(old)
                .thenThrow(new IllegalStateException("offline"));
        manager.getBars("TEST", "M1", 10);
        assertEquals(old, manager.refreshBars("TEST", "M1", 10));
        manager.refreshBars("TEST", "M1", 10);
        verify(market, times(2)).fetchOptionBars("TEST", 200, "M1");
    }
}
