package com.trading.option;

import com.trading.broker.BrokerClient;
import com.trading.config.AppProperties;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionExpiryDiscoveryTest {
    @Test void picksNearestActualDateRatherThanCalendarDayAndCachesPerUnderlyingSession() {
        var broker = mock(BrokerClient.class);
        var service = new OptionContractService(null, null, null, broker);
        var thursday = LocalDate.of(2026, 10, 15);
        var friday = thursday.plusDays(1);
        when(broker.fetchOptionExpirations("AAPL", thursday)).thenReturn(List.of(friday.plusDays(7), friday, thursday.minusDays(1)));
        when(broker.fetchOptionExpirations("SPY", thursday)).thenReturn(List.of(thursday, friday));
        assertEquals(friday, service.resolveExpiry("aapl", thursday));
        assertEquals(friday, service.resolveExpiry("AAPL", thursday));
        assertEquals(thursday, service.resolveExpiry("SPY", thursday));
        verify(broker, times(1)).fetchOptionExpirations("AAPL", thursday);
    }
    @Test void replayUsesItsSessionEvenWhenRequestedAfterExpiration() {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.options().defaultStrikeIncrement()).thenReturn(BigDecimal.ONE);
        when(props.options().strikesPerSide()).thenReturn(1);
        var universe = mock(OptionUniverseLoader.class);
        var broker = mock(BrokerClient.class);
        var session = LocalDate.of(2026, 10, 15);
        when(broker.fetchOptionExpirations("AAPL", session)).thenReturn(List.of(session.plusDays(1), session.plusDays(8)));
        var service = new OptionContractService(props, universe, null, broker);
        var generated = service.generateContracts("AAPL", BigDecimal.TEN, Set.of(OptionContract.Type.CALL), session);
        assertFalse(generated.isEmpty());
        assertTrue(generated.stream().allMatch(c -> c.expiry().equals(session.plusDays(1))));
    }
    @Test void unavailableMetadataNeverFallsBackToGuessedExpiryAndFailuresAreThrottled() {
        var broker = mock(BrokerClient.class);
        var service = new OptionContractService(null, null, null, broker);
        var session = LocalDate.of(2026, 10, 15);
        when(broker.fetchOptionExpirations("AAPL", session)).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> service.resolveExpiry("AAPL", session));
        assertThrows(IllegalStateException.class, () -> service.resolveExpiry("AAPL", session));
        verify(broker, times(1)).fetchOptionExpirations("AAPL", session);
    }
    @Test void liveUsesSameResolverAndReportsUnavailableExpiryWithoutLoadingBars() {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        var universe = mock(OptionUniverseLoader.class);
        var broker = mock(BrokerClient.class);
        var bars = mock(OptionBarDataManager.class);
        var today = LocalDate.now(java.time.ZoneId.of("America/New_York"));
        when(broker.fetchFillPrice("AAPL", null)).thenReturn(BigDecimal.TEN);
        when(broker.fetchOptionExpirations("AAPL", today)).thenReturn(List.of());
        var service = new OptionContractService(props, universe, bars, broker);
        var result = service.loadUnderlying("AAPL", Set.of(OptionContract.Type.CALL));
        assertNull(result.expiry());
        assertTrue(result.error().contains("No listed expiration"));
        verifyNoInteractions(bars);
    }
}
