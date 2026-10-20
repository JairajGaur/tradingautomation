package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionEmaTimeframeTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final BarDataManager stock = mock(BarDataManager.class);
    private final Instant start = Instant.parse("2026-10-16T13:30:00Z");
    private final Instant asOf = start.plusSeconds(3600);
    private OptionStockTrendFilter filter(String frames) {
        var cfg = props.alerts().optionVolSt();
        when(cfg.timeframe()).thenReturn("M1");
        when(cfg.stockEmaStackTimeframe()).thenReturn(frames);
        when(cfg.stockHigherStTimeframe()).thenReturn(frames);
        when(cfg.stockEmaStackFast()).thenReturn(2);
        when(cfg.stockEmaStackSlow()).thenReturn(3);
        when(cfg.barQuality().maxAgeBars()).thenReturn(1);
        when(cfg.barQuality().gapWindow()).thenReturn(20);
        when(props.engine().warmupBars()).thenReturn(100);
        return new OptionStockTrendFilter(props,stock);
    }
    private List<Candle> history(int minutes, boolean up) {
        var result = new ArrayList<Candle>();
        for (int i=0;i<60/minutes;i++) {
            var p=BigDecimal.valueOf(up ? 100+i*minutes : 200-i*minutes);
            result.add(new Candle("AAPL",start.plusSeconds(i*minutes*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100));
        }
        return result;
    }
    @Test void nativeStockTimeframesConfirmCallAndPutIndependentlyOfOptionTimeframe() {
        var filter=filter("M1,M5");
        when(props.alerts().optionVolSt().timeframe()).thenReturn("M15");
        for (boolean up : List.of(true,false)) {
            when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,up));
            when(stock.getBars("AAPL","M5",100)).thenReturn(history(5,up));
            var series=filter.load("AAPL",true);
            assertEquals(up,series.matches(OptionContract.Type.CALL,asOf));
            assertEquals(!up,series.matches(OptionContract.Type.PUT,asOf));
        }
    }
    @Test void everyStockIntervalMustAgreeAndUnavailableOneBlocksBothSides() {
        var filter=filter(" m01,M1, m5 ");
        when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,true));
        when(stock.getBars("AAPL","M5",100)).thenReturn(history(5,false));
        var series=filter.load("AAPL",true);
        assertEquals(2,series.frames().size());
        assertFalse(series.matches(OptionContract.Type.CALL,asOf));
        assertFalse(series.matches(OptionContract.Type.PUT,asOf));
        when(stock.getBars("AAPL","M5",100)).thenReturn(List.of());
        assertNull(filter.load("AAPL",true).matches(OptionContract.Type.CALL,asOf));
    }
    @Test void stockHigherStConfirmsDirectionalSideOnAllIntervals() {
        var filter=filter("M1,M5");
        for (boolean up : List.of(true,false)) {
            when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,up));
            when(stock.getBars("AAPL","M5",100)).thenReturn(history(5,up));
            var series=filter.load("AAPL",false);
            assertEquals(up,series.matches(OptionContract.Type.CALL,asOf));
            assertEquals(!up,series.matches(OptionContract.Type.PUT,asOf));
        }
    }
    @Test void formingOrFutureCandlesCannotChangeHistoricalConfirmation() {
        var filter=filter("M5");
        var input=history(5,false);
        when(stock.getBars("AAPL","M5",100)).thenReturn(input);
        var prefix=filter.load("AAPL",true);
        var p=new BigDecimal("99999");
        input.add(new Candle("AAPL",asOf,p,p,p,p,100));
        var series=filter.load("AAPL",true);
        assertEquals(prefix.matches(OptionContract.Type.PUT,asOf),series.matches(OptionContract.Type.PUT,asOf));
        assertTrue(series.matches(OptionContract.Type.PUT,asOf));
    }
    @Test void staleStockHistoryRecoversButValidOpposingTrendDoesNotRefetch() {
        var filter=filter("M1");
        when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,true).subList(0,20));
        when(stock.refreshBars("AAPL","M1",1650)).thenReturn(history(1,true));
        assertTrue(filter.current("AAPL",OptionContract.Type.CALL,asOf,true));
        verify(stock).refreshBars("AAPL","M1",1650);
        clearInvocations(stock);
        when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,false));
        assertFalse(filter.current("AAPL",OptionContract.Type.CALL,asOf,true));
        verify(stock,never()).refreshBars(anyString(),anyString(),anyInt());
    }
    @Test void syntheticStockIntervalsRequireCompleteBucketsAndWarmup() {
        var filter=filter("M3");
        var source=history(1,true);
        when(stock.getBars("AAPL","M1",100)).thenReturn(source);
        assertTrue(filter.load("AAPL",true).matches(OptionContract.Type.CALL,asOf));
        source.remove(58);
        assertNull(filter.load("AAPL",true).matches(OptionContract.Type.CALL,asOf));
        when(stock.getBars("AAPL","M1",100)).thenReturn(history(1,true).subList(0,4));
        assertNull(filter.load("AAPL",true).matches(OptionContract.Type.CALL,start.plusSeconds(240)));
    }
    @Test void malformedTimeframesAndEqualEmasCannotConfirm() {
        assertNull(filter("M1,bad").load("AAPL",true).matches(OptionContract.Type.CALL,asOf));
        var filter=filter("M1");
        var flat=history(1,true).stream().map(c->new Candle("AAPL",c.timestamp(),BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,100)).toList();
        when(stock.getBars("AAPL","M1",100)).thenReturn(flat);
        var series=filter.load("AAPL",true);
        assertFalse(series.matches(OptionContract.Type.CALL,asOf));
        assertFalse(series.matches(OptionContract.Type.PUT,asOf));
    }
    @Test void renamedPropertiesBindToStockConfirmationSettings() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("stock-ema-stack-enabled", "true", "stock-ema-stack-timeframe", "M1,M30",
                        "stock-ema-stack-fast", "9", "stock-ema-stack-slow", "20",
                        "stock-higher-st-enabled", "true", "stock-higher-st-timeframe", "M5,M15"));
        var cfg = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("", org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertTrue(cfg.stockEmaStackEnabled());
        assertEquals("M1,M30",cfg.stockEmaStackTimeframe());
        assertEquals(9,cfg.stockEmaStackFast());
        assertEquals(20,cfg.stockEmaStackSlow());
        assertTrue(cfg.stockHigherStEnabled());
        assertEquals("M5,M15",cfg.stockHigherStTimeframe());
    }
    @Test void springWiresTheStockFilterIntoTheLiveAlert() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(AppProperties.class, () -> props);
            context.registerBean(BarDataManager.class, () -> stock);
            context.registerBean(OptionUniverseLoader.class, () -> mock(OptionUniverseLoader.class));
            context.registerBean(OptionContractService.class, () -> mock(OptionContractService.class));
            context.registerBean(OptionBarDataManager.class, () -> mock(OptionBarDataManager.class));
            context.registerBean(com.trading.indicator.SupertrendService.class, () -> mock(com.trading.indicator.SupertrendService.class));
            context.registerBean(OptionHaDirectionFilter.class, () -> mock(OptionHaDirectionFilter.class));
            context.registerBean(com.trading.service.StockPivotService.class, () -> mock(com.trading.service.StockPivotService.class));
            context.registerBean(com.trading.llm.PivotAlertNarrator.class, () -> mock(com.trading.llm.PivotAlertNarrator.class));
            context.register(OptionStockTrendFilter.class,OptionVolumeStAlert.class);
            context.refresh();
            assertSame(context.getBean(OptionStockTrendFilter.class),
                    org.springframework.test.util.ReflectionTestUtils.getField(context.getBean(OptionVolumeStAlert.class),"stockTrend"));
        }
    }
}
