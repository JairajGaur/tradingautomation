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

    @Test
    void optionalPivotNarrationNeverSuppressesAnOtherwiseQualifyingGroup() {
        var props=mock(AppProperties.class,RETURNS_DEEP_STUBS);
        var pivots=mock(com.trading.service.StockPivotService.class);
        var narrator=mock(com.trading.llm.PivotAlertNarrator.class);
        var alert=new OptionVolumeStAlert(props,null,null,null,null,null,pivots,null,narrator);
        var group=new com.trading.alert.AlertHit("option-vol-st","AAPL","OPTGROUP|AAPL|CALL|123",
                "OPTION - AAPL CALL\nQualifying contracts",com.trading.alert.AlertMessageFormat.Dir.UP,null,"M1");
        var asOf=Instant.parse("2026-10-19T15:00:00Z");
        when(pivots.current("AAPL",asOf)).thenThrow(new RuntimeException("stock unavailable"));
        assertSame(group,ReflectionTestUtils.invokeMethod(alert,"withPivotNarrative","AAPL",group,asOf));
        reset(pivots);
        when(narrator.summarize(eq("AAPL"),eq(OptionContract.Type.CALL),isNull(),any())).thenThrow(new RuntimeException("LLM unavailable"));
        assertSame(group,ReflectionTestUtils.invokeMethod(alert,"withPivotNarrative","AAPL",group,asOf));
        reset(narrator);
        when(narrator.summarize(eq("AAPL"),eq(OptionContract.Type.CALL),isNull(),any())).thenReturn("");
        assertSame(group,ReflectionTestUtils.invokeMethod(alert,"withPivotNarrative","AAPL",group,asOf));
        when(narrator.summarize(eq("AAPL"),eq(OptionContract.Type.CALL),isNull(),any())).thenReturn("\nPivot view: Stock is above daily pivot.");
        com.trading.alert.AlertHit enriched=ReflectionTestUtils.invokeMethod(alert,"withPivotNarrative","AAPL",group,asOf);
        assertEquals(group.state(),enriched.state());assertEquals(group.ticker(),enriched.ticker());
        assertEquals(group.direction(),enriched.direction());assertTrue(enriched.message().startsWith(group.message()));
        assertFalse(enriched.message().contains("S1"));
    }
}
