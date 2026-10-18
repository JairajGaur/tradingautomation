package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionsReplaySessionTest {
    private final LocalDate friday = LocalDate.of(2026, 10, 16);

    private Candle opening(LocalDate day) {
        return new Candle("AAPL", day.atTime(LocalTime.of(9, 30))
                .atZone(ZoneId.of("America/New_York")).toInstant(),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100);
    }

    @Test
    void weekendAndPreopenUseLatestActualSession() {
        var history = Map.of("AAPL", List.of(opening(friday.minusDays(1)), opening(friday)));
        assertEquals(friday, OptionsController.latestReplaySession(history, friday.plusDays(2)));
        assertEquals(friday, OptionsController.latestReplaySession(history, friday.plusDays(3)));
    }

    @Test
    void holidayGapAndUnsortedBarsUseLatestOpening() {
        LocalDate tuesday = LocalDate.of(2026, 9, 8);
        LocalDate previousFriday = LocalDate.of(2026, 9, 4);
        var history = Map.of("AAPL", List.of(opening(previousFriday), opening(previousFriday.minusDays(1))));
        assertEquals(previousFriday, OptionsController.latestReplaySession(history, tuesday));
    }

    @Test
    void todayWinsWhenItsOpeningExistsAndFutureBarsAreIgnored() {
        var history = Map.of("AAPL", List.of(opening(friday.plusDays(3)), opening(friday)));
        assertEquals(friday, OptionsController.latestReplaySession(history, friday));
        assertNull(OptionsController.latestReplaySession(Map.of(), friday));
    }

    @Test
    void responseAndContractExpiryUseSelectedSession() {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        var data = mock(MarketDataService.class);
        var contracts = mock(OptionContractService.class);
        var alert = mock(OptionVolumeStAlert.class);
        when(alert.optionTimeframe()).thenReturn("M1");
        when(data.fetchHistoricalBars("AAPL", 1650, "M1", "RTH"))
                .thenReturn(List.of(opening(friday)));
        var contract = OptionContract.of("AAPL", friday, OptionContract.Type.CALL,
                BigDecimal.TEN, OptionContract.Moneyness.ATM);
        when(contracts.generateContracts(eq("AAPL"), eq(BigDecimal.TEN), anySet(), eq(friday)))
                .thenReturn(List.of(contract));
        var optionBars = List.of(opening(friday));
        when(data.fetchOptionBars(contract.occSymbol(), 1200, "M1")).thenReturn(optionBars);
        when(alert.replayContract(contract, optionBars, friday)).thenReturn(
                new OptionVolumeStAlert.ContractReplay(contract.occSymbol(), "CALL", "10", "ATM", 1, List.of()));
        var controller = new OptionsController(props, null, null, contracts, null, alert, data, null);
        var body = controller.volStReplay("AAPL").getBody();
        assertNotNull(body);
        assertEquals(friday.toString(), body.get("date"));
        verify(contracts).generateContracts(eq("AAPL"), eq(BigDecimal.TEN), anySet(), eq(friday));
        verify(alert).replayContract(contract, optionBars, friday);
    }

    @Test
    void missingHistoryDoesNotInventSessionOrGenerateContracts() {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        var data = mock(MarketDataService.class);
        var contracts = mock(OptionContractService.class);
        var alert = mock(OptionVolumeStAlert.class);
        when(alert.optionTimeframe()).thenReturn("M1");
        when(data.fetchHistoricalBars("AAPL", 1650, "M1", "RTH"))
                .thenThrow(new IllegalStateException("offline"));
        var controller = new OptionsController(props, null, null, contracts, null, alert, data, null);
        var body = controller.volStReplay("AAPL").getBody();
        assertNotNull(body);
        assertNull(body.get("date"));
        assertEquals(0, body.get("totalSignals"));
        assertTrue(body.get("results").toString().contains("stock history unavailable"));
        verifyNoInteractions(contracts);
    }

    @Test
    void replayGeneratesFridayExpiryInsteadOfRequestWeekExpiry() {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        var universe = mock(OptionUniverseLoader.class);
        when(props.options().dte()).thenReturn(0);
        when(props.options().defaultStrikeIncrement()).thenReturn(BigDecimal.ONE);
        when(props.options().strikesPerSide()).thenReturn(1);
        var service = new OptionContractService(props, universe, null, null);
        var generated = service.generateContracts("AAPL", BigDecimal.TEN,
                Set.of(OptionContract.Type.CALL), friday);
        assertFalse(generated.isEmpty());
        assertTrue(generated.stream().allMatch(c -> c.expiry().equals(friday)));
    }
}
