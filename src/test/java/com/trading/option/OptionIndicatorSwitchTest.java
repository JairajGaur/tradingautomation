package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionIndicatorSwitchTest {
    private final AppProperties props=mock(AppProperties.class,RETURNS_DEEP_STUBS);
    private final OptionBarDataManager bars=mock(OptionBarDataManager.class);
    private final Instant start=Instant.parse("2026-10-19T13:30:00Z");
    private final Instant asOf=start.plusSeconds(3600);
    private final LocalDate day=LocalDate.of(2026,10,19);
    private final OptionContract contract=OptionContract.of("AAPL",day,OptionContract.Type.CALL,BigDecimal.valueOf(100),OptionContract.Moneyness.ATM);
    private OptionVolumeStAlert alert() {
        var c=props.alerts().optionVolSt();
        when(c.optionStEnabled()).thenReturn(true);
        when(c.stRisingEnabled()).thenReturn(true);
        when(c.emaAboveEnabled()).thenReturn(true);

        when(c.timeframe()).thenReturn("M1");when(c.emaPeriod()).thenReturn(20);when(c.emaAboveCandles()).thenReturn(3);when(c.stRisingCandles()).thenReturn(3);
        when(props.engine().warmupBars()).thenReturn(100);when(props.supertrend().length()).thenReturn(7);when(props.supertrend().factor()).thenReturn(BigDecimal.valueOf(2));
        return new OptionVolumeStAlert(props,null,null,bars,new SupertrendService(props),null);
    }
    private List<Candle> history(boolean up) {
        var out=new ArrayList<Candle>();
        for(int i=0;i<61;i++){
            var p=BigDecimal.valueOf(up?100+i:200-i);
            out.add(new Candle(contract.occSymbol(),start.plusSeconds(i*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100));
        }
        return out;
    }
    private Object live(OptionVolumeStAlert a) { return ReflectionTestUtils.invokeMethod(a,"evaluateContract",contract,"M1",day.toString(),props.alerts().optionVolSt(),asOf); }
    private OptionVolumeStAlert.LegEval inspection(OptionVolumeStAlert a) { return ReflectionTestUtils.invokeMethod(a,"evaluateLegs",contract,asOf); }
    @Test void defaultOnAndExplicitOffBindWithoutChangingLegacyBehavior() {
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("enabled","true"));
        var cfg=new org.springframework.boot.context.properties.bind.Binder(source).bind("",org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertTrue(cfg.optionStEnabled());assertTrue(cfg.stRisingEnabled());assertTrue(cfg.emaAboveEnabled());
        source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("option-st-enabled","false","st-rising-enabled","false","ema-above-enabled","false"));
        cfg=new org.springframework.boot.context.properties.bind.Binder(source).bind("",org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertFalse(cfg.optionStEnabled());assertFalse(cfg.stRisingEnabled());assertFalse(cfg.emaAboveEnabled());
    }
    @Test void disablingStAndCloseEmaBypassesBothAcrossLiveInspectionAndReplay() {
        var a=alert();var source=history(false);when(bars.getBars(contract.occSymbol(),"M1",100)).thenReturn(source);
        assertNull(live(a));assertFalse(inspection(a).qualifies());assertTrue(a.replayCandidates(contract,source,day).signals().isEmpty());
        when(props.alerts().optionVolSt().optionStEnabled()).thenReturn(false);
        when(props.alerts().optionVolSt().emaAboveEnabled()).thenReturn(false);
        assertNotNull(live(a));assertTrue(inspection(a).qualifies());assertFalse(a.replayCandidates(contract,source,day).signals().isEmpty());
        assertFalse(a.stRisingEnabled());
    }
    @Test void closeEmaSwitchIsIndependentOfSupertrendMaster() {
        var a=alert();when(bars.getBars(contract.occSymbol(),"M1",100)).thenReturn(history(false));
        when(props.alerts().optionVolSt().optionStEnabled()).thenReturn(false);
        assertNull(live(a)); // EMA still rejects the declining option.
        when(props.alerts().optionVolSt().emaAboveEnabled()).thenReturn(false);assertNotNull(live(a));
        when(props.alerts().optionVolSt().optionStEnabled()).thenReturn(true);assertNull(live(a)); // ST direction still rejects.
    }
    @Test void risingSwitchCanBypassStreakWhileKeepingDirectionEnabled() {
        var a=alert();when(bars.getBars(contract.occSymbol(),"M1",100)).thenReturn(history(true));
        when(props.alerts().optionVolSt().stRisingCandles()).thenReturn(50);
        assertNull(live(a));
        when(props.alerts().optionVolSt().stRisingEnabled()).thenReturn(false);
        assertNotNull(live(a));assertTrue(a.optionStEnabled());assertFalse(a.stRisingEnabled());
        assertTrue(inspection(a).qualifies());assertFalse(a.replayCandidates(contract,history(true),day).signals().isEmpty());
    }
    @Test void disabledIndicatorsDoNotRequireTheirWarmupButIndependentAtrStillDoes() {
        var a=alert();var c=props.alerts().optionVolSt();
        when(c.optionStEnabled()).thenReturn(false);when(c.emaAboveEnabled()).thenReturn(false);when(c.emaPeriod()).thenReturn(500);
        assertEquals(2,(Integer)ReflectionTestUtils.invokeMethod(a,"minimumBars"));
        when(c.largeCandleFilterEnabled()).thenReturn(true);when(c.maxCandleAtr()).thenReturn(new BigDecimal("3"));
        assertEquals(7,(Integer)ReflectionTestUtils.invokeMethod(a,"minimumBars"));
    }
}
