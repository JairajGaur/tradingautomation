package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OptionPivotFilterTest {
    private final ZoneId et=ZoneId.of("America/New_York");
    private final LocalDate day=LocalDate.of(2026,10,19);
    private final Instant start=day.atTime(9,30).atZone(et).toInstant();
    private final Instant asOf=start.plusSeconds(120*60);
    private final AppProperties.Alerts.BarQualityCfg quality=new AppProperties.Alerts.BarQualityCfg(1,2,20,1,0,20);
    private AppProperties.Alerts.PivotFilterCfg cfg(boolean enabled,String minimum) {
        return new AppProperties.Alerts.PivotFilterCfg(enabled,"M5",14,new BigDecimal(minimum));
    }
    private List<Candle> history(String current) {
        var out=new ArrayList<Candle>();
        var prior=day.minusDays(3).atTime(9,30).atZone(et).toInstant();
        for (int i=0;i<390;i++) out.add(new Candle("AAPL",prior.plusSeconds(i*60L),new BigDecimal("100"),new BigDecimal("110"),new BigDecimal("90"),new BigDecimal("100"),100));
        var p=new BigDecimal(current);
        for (int i=0;i<120;i++)out.add(new Candle("AAPL",start.plusSeconds(i*60L),p,p.add(new BigDecimal("0.1")),p.subtract(new BigDecimal("0.1")),p,100));
        return out;
    }
    private OptionPivotFilter.Decision decision(String price,OptionContract.Type side,String min) {
        return new OptionPivotFilter.Series("AAPL",history(price),day,cfg(true,min),quality).at(side,asOf);
    }
    @Test void disabledGateDoesNotRequireDataOrValidThreshold() {
        assertTrue(new OptionPivotFilter.Series("AAPL",null,day,null,quality).at(OptionContract.Type.CALL,asOf).passes());
        assertTrue(new OptionPivotFilter.Series("AAPL",null,day,cfg(false,"-1"),quality).at(OptionContract.Type.PUT,asOf).passes());
    }
    @Test void callRejectsNearbyResistanceWhilePutHasRoomToLowerPivot() {
        var call=decision("109.99",OptionContract.Type.CALL,"0.5");
        assertFalse(call.passes());assertEquals("R1",call.nextLevel());assertEquals("PIVOT_TOO_CLOSE",call.reason());
        assertEquals("ABOVE_P",call.pivotBias());assertNotNull(call.stockAtr());
        var put=decision("109.99",OptionContract.Type.PUT,"0.5");
        assertTrue(put.passes());assertEquals("P",put.nextLevel());
        assertEquals(day.minusDays(3),put.pivotSourceSession());
    }
    @Test void putRejectsNearbySupportWhileCallHasRoomAbove() {
        assertFalse(decision("90.01",OptionContract.Type.PUT,"0.5").passes());
        assertEquals("S1",decision("90.01",OptionContract.Type.PUT,"0.5").nextLevel());
        assertTrue(decision("90.01",OptionContract.Type.CALL,"0.5").passes());
    }
    @Test void atLevelHasZeroRoomAndNoInventedBoundaryBeyondOuterLevels() {
        var at=decision("110",OptionContract.Type.CALL,"0.5");
        assertFalse(at.passes());assertEquals(0,at.distance().signum());
        assertTrue(decision("110",OptionContract.Type.CALL,"0").passes());
        var outside=decision("121",OptionContract.Type.CALL,"0.5");
        assertTrue(outside.passes());assertEquals("NO_KNOWN_PIVOT_AHEAD",outside.reason());assertNull(outside.roomAtr());
        assertTrue(decision("79",OptionContract.Type.PUT,"0.5").passes());
    }
    @Test void futureStockCandlesCannotChangeHistoricalDecision() {
        var bars=history("105");var series=new OptionPivotFilter.Series("AAPL",bars,day,cfg(true,"0.5"),quality);
        var original=series.at(OptionContract.Type.CALL,asOf);
        var p=new BigDecimal("99999");bars.add(new Candle("AAPL",asOf,p,p,p,p,100));
        bars.add(new Candle("AAPL",asOf.plusSeconds(60),p,p,p,p,100));
        assertEquals(original,series.at(OptionContract.Type.CALL,asOf));
    }
    @Test void missingPriorSessionStaleStockAndIncompleteAtrBucketsFailClosed() {
        var bars=history("105");bars.remove(0);
        assertEquals("PIVOT_LEVELS_UNAVAILABLE",new OptionPivotFilter.Series("AAPL",bars,day,cfg(true,"0.5"),quality).at(OptionContract.Type.CALL,asOf).reason());
        bars=history("105");bars.subList(bars.size()-20,bars.size()).clear();
        assertFalse(new OptionPivotFilter.Series("AAPL",bars,day,cfg(true,"0.5"),quality).at(OptionContract.Type.CALL,asOf).passes());
        bars=history("105");bars.remove(bars.size()-3);
        var result=new OptionPivotFilter.Series("AAPL",bars,day,cfg(true,"0.5"),quality).at(OptionContract.Type.CALL,asOf);
        assertFalse(result.passes());assertTrue(result.reason().startsWith("PIVOT_ATR_"));
    }
    @Test void invalidConfigFailsClosedAndStockPriceNotOptionPremiumDefinesBoundary() {
        var invalid=new AppProperties.Alerts.PivotFilterCfg(true,"D",14,new BigDecimal("0.5"));
        assertEquals("PIVOT_INVALID_CONFIG",new OptionPivotFilter.Series("AAPL",history("105"),day,invalid,quality).at(OptionContract.Type.CALL,asOf).reason());
        var result=decision("105",OptionContract.Type.CALL,"0.5");
        assertEquals(new BigDecimal("105"),result.stockPrice());assertEquals("R1",result.nextLevel());
    }
    @Test void pivotSwitchAndDecisionAreIdenticalForLiveInspectionAndReplay() {
        var props=org.mockito.Mockito.mock(AppProperties.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var cfg=props.alerts().optionVolSt();
        org.mockito.Mockito.when(cfg.optionStEnabled()).thenReturn(true);
        org.mockito.Mockito.when(cfg.stRisingEnabled()).thenReturn(true);
        org.mockito.Mockito.when(cfg.emaAboveEnabled()).thenReturn(true);
        org.mockito.Mockito.when(cfg.timeframe()).thenReturn("M1");
        org.mockito.Mockito.when(cfg.emaPeriod()).thenReturn(3);
        org.mockito.Mockito.when(cfg.emaAboveCandles()).thenReturn(1);
        org.mockito.Mockito.when(cfg.stRisingCandles()).thenReturn(1);
        org.mockito.Mockito.when(cfg.barQuality()).thenReturn(quality);
        org.mockito.Mockito.when(cfg.pivotFilter()).thenReturn(cfg(true,"0.5"));
        org.mockito.Mockito.when(props.engine().warmupBars()).thenReturn(200);
        org.mockito.Mockito.when(props.supertrend().length()).thenReturn(3);
        org.mockito.Mockito.when(props.supertrend().factor()).thenReturn(BigDecimal.ONE);
        var options=org.mockito.Mockito.mock(OptionBarDataManager.class);
        var pivots=org.mockito.Mockito.mock(com.trading.service.StockPivotService.class);
        org.mockito.Mockito.when(pivots.history("AAPL")).thenReturn(history("109.99"));
        var contract=OptionContract.of("AAPL",day,OptionContract.Type.CALL,BigDecimal.valueOf(110),OptionContract.Moneyness.ATM);
        var premiums=new ArrayList<Candle>();
        for (int i=0;i<121;i++) {
            var p=BigDecimal.valueOf(10+i);
            premiums.add(new Candle(contract.occSymbol(),start.plusSeconds(i*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100));
        }
        org.mockito.Mockito.when(options.getBars(contract.occSymbol(),"M1",200)).thenReturn(premiums);
        var alert=new OptionVolumeStAlert(props,null,null,options,new com.trading.indicator.SupertrendService(props),null,pivots);
        assertNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(alert,"evaluateContract",contract,"M1",day.toString(),cfg,asOf));
        OptionVolumeStAlert.LegEval inspection=org.springframework.test.util.ReflectionTestUtils.invokeMethod(alert,"evaluateLegs",contract,asOf);
        assertFalse(inspection.qualifies());assertEquals("PIVOT_TOO_CLOSE",inspection.pivotFilter().reason());
        var replay=alert.replayCandidates(contract,premiums,day,history("109.99"));
        assertTrue(replay.signals().isEmpty());assertTrue(replay.dataQualityRejections().containsKey("PIVOT_TOO_CLOSE"));
        org.mockito.Mockito.when(cfg.pivotFilter()).thenReturn(cfg(false,"0.5"));
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(alert,"evaluateContract",contract,"M1",day.toString(),cfg,asOf));
        inspection=org.springframework.test.util.ReflectionTestUtils.invokeMethod(alert,"evaluateLegs",contract,asOf);
        assertTrue(inspection.qualifies());
        assertFalse(alert.replayCandidates(contract,premiums,day,List.of()).signals().isEmpty());
    }
    @Test void pivotSwitchBindsAndDefaultsToDisabledWhenOmitted() {
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "enabled","true","pivot-filter.enabled","true","pivot-filter.atr-timeframe","M5",
                "pivot-filter.atr-period","14","pivot-filter.min-room-atr","0.5"));
        var binder=new org.springframework.boot.context.properties.bind.Binder(source);
        var cfg=binder.bind("",org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertTrue(cfg.pivotFilter().enabled());assertEquals("M5",cfg.pivotFilter().atrTimeframe());
        assertEquals(14,cfg.pivotFilter().atrPeriod());assertEquals(new BigDecimal("0.5"),cfg.pivotFilter().minRoomAtr());
        var empty=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of("enabled","true"));
        cfg=new org.springframework.boot.context.properties.bind.Binder(empty).bind("",org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertFalse(cfg.pivotFilter().enabled());
    }
}
