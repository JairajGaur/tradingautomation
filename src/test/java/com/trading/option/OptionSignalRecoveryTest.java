package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.BarQuality;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionSignalRecoveryTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final OptionBarDataManager bars = mock(OptionBarDataManager.class);
    private final Instant start = Instant.parse("2026-10-16T13:30:00Z");
    private OptionVolumeStAlert alert() {
        when(props.alerts().optionVolSt().optionStEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().stRisingEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().emaAboveEnabled()).thenReturn(true);

        when(props.alerts().optionVolSt().timeframe()).thenReturn("M1");
        when(props.alerts().optionVolSt().emaPeriod()).thenReturn(20);
        when(props.alerts().optionVolSt().emaAboveCandles()).thenReturn(3);
        when(props.alerts().optionVolSt().barQuality().gapWindow()).thenReturn(20);
        when(props.alerts().optionVolSt().barQuality().maxMissingBars()).thenReturn(1);
        when(props.supertrend().length()).thenReturn(7);
        when(props.supertrend().factor()).thenReturn(BigDecimal.valueOf(2));
        when(props.engine().warmupBars()).thenReturn(100);
        return new OptionVolumeStAlert(props, null, null, bars, new SupertrendService(props), null);
    }
    private List<Candle> history(int count) {
        var list = new ArrayList<Candle>();
        for (int i = 0; i < count; i++) {
            var price = BigDecimal.valueOf(10 + i);
            list.add(new Candle("TEST", start.plusSeconds(i * 60L), price,
                    price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price, 100));
        }
        return list;
    }
    private BarQuality.Result quality(OptionVolumeStAlert alert, int minute) {
        return ReflectionTestUtils.invokeMethod(alert, "liveBars", "TEST", "M1", start.plusSeconds(minute * 60L));
    }

    @Test
    void staleDataRecoversAndCompletedPriceExcludesFormingCandle() {
        var alert = alert();
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(30));
        when(bars.refreshBars("TEST", "M1", 1200)).thenReturn(history(61));
        var result = quality(alert, 60);
        assertTrue(result.valid());
        assertEquals(60, result.completed().size());
        assertEquals(BigDecimal.valueOf(69), result.completed().get(59).close());
        verify(bars).refreshBars("TEST", "M1", 1200);
    }

    @Test
    void unrecoveredStaleDataIsRejected() {
        var alert = alert();
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(30));
        when(bars.refreshBars("TEST", "M1", 1200)).thenReturn(history(30));
        assertEquals("STALE_BARS", quality(alert, 60).reason());
    }

    @Test
    void healthyCompletedOnlyResponseNeedsNoRefresh() {
        var alert = alert();
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(60));
        assertTrue(quality(alert, 60).valid());
        verify(bars, never()).refreshBars(anyString(), anyString(), anyInt());
    }

    @Test
    void stockTimeframesDoNotIncreaseOptionWarmupOrRequireOptionAggregation() {
        var alert = alert();
        when(props.alerts().optionVolSt().stockHigherStEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().stockHigherStTimeframe()).thenReturn("M30,M60");
        when(props.alerts().optionVolSt().stockEmaStackEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().stockEmaStackTimeframe()).thenReturn("M30,M60");
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(60));
        assertTrue(quality(alert, 60).valid());
        verify(bars, never()).refreshBars(anyString(), anyString(), anyInt());
    }

    @Test
    void liveAlertUsesTheLatestCompletedPriceAndKeepsHealthySignal() {
        var alert = alert();
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(61));
        var contract = new OptionContract("TEST", "AAPL", java.time.LocalDate.of(2026, 10, 16),
                OptionContract.Type.CALL, BigDecimal.TEN, OptionContract.Moneyness.ATM);
        com.trading.alert.AlertHit hit = ReflectionTestUtils.invokeMethod(alert, "evaluateContract", contract,
                "M1", "2026-10-16", props.alerts().optionVolSt(), start.plusSeconds(3600));
        assertNotNull(hit);
        assertTrue(hit.message().endsWith("69")); // forming bar's close is 70
        verify(bars, never()).refreshBars(anyString(), anyString(), anyInt());
    }

    @Test
    void atrCapIsConsistentForLiveAndInspectionAndCanBeDisabled() {
        var alert = alert();
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(new BigDecimal("3"));
        var source = history(61);
        var spike = source.get(59);
        source.set(59, new Candle("TEST", spike.timestamp(), spike.open(), new BigDecimal("100"),
                new BigDecimal("60"), spike.close(), 100));
        when(bars.getBars("TEST", "M1", 100)).thenReturn(source);
        var contract = new OptionContract("TEST", "AAPL", java.time.LocalDate.of(2026, 10, 16),
                OptionContract.Type.CALL, BigDecimal.TEN, OptionContract.Moneyness.ATM);
        var asOf = start.plusSeconds(3600);
        assertNotNull(ReflectionTestUtils.invokeMethod(alert, "evaluateContract", contract,
                "M1", "2026-10-16", props.alerts().optionVolSt(), asOf));
        when(props.alerts().optionVolSt().largeCandleFilterEnabled()).thenReturn(true);
        assertNull(ReflectionTestUtils.invokeMethod(alert, "evaluateContract", contract,
                "M1", "2026-10-16", props.alerts().optionVolSt(), asOf));
        OptionVolumeStAlert.LegEval inspection = ReflectionTestUtils.invokeMethod(alert, "evaluateLegs", contract, asOf);
        assertNotNull(inspection);
        assertFalse(inspection.qualifies());
        assertFalse(inspection.candleSizeOk());
        assertTrue(inspection.candleRangeAtr().compareTo(new BigDecimal("3")) > 0);
        assertTrue(inspection.note().contains("climax bar"));
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(BigDecimal.ZERO);
        assertNotNull(ReflectionTestUtils.invokeMethod(alert, "evaluateContract", contract,
                "M1", "2026-10-16", props.alerts().optionVolSt(), asOf));
    }

    @Test
    void atrCapDoesNotUseFormingOptionCandle() {
        var alert = alert();
        when(props.alerts().optionVolSt().largeCandleFilterEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(new BigDecimal("3"));
        var source = history(61);
        var forming = source.get(60);
        source.set(60, new Candle("TEST", forming.timestamp(), forming.open(), new BigDecimal("9999"),
                BigDecimal.ONE, forming.close(), 100));
        when(bars.getBars("TEST", "M1", 100)).thenReturn(source);
        var contract = new OptionContract("TEST", "AAPL", java.time.LocalDate.of(2026, 10, 16),
                OptionContract.Type.CALL, BigDecimal.TEN, OptionContract.Moneyness.ATM);
        OptionVolumeStAlert.LegEval inspection = ReflectionTestUtils.invokeMethod(alert, "evaluateLegs", contract, start.plusSeconds(3600));
        assertNotNull(inspection);
        assertTrue(inspection.qualifies());
        assertEquals(new BigDecimal("1.0000"), inspection.candleRangeAtr());
    }

    @Test
    void shortActualHistoryAttemptsRecoveryAndStillRequiresIndicatorMinimum() {
        var alert = alert();
        when(bars.getBars("TEST", "M1", 100)).thenReturn(history(10));
        when(bars.refreshBars("TEST", "M1", 1200)).thenReturn(history(10));
        assertEquals("INSUFFICIENT_WARMUP", quality(alert, 10).reason());
    }
    @Test
    void rankingVolumeIsCapturedFromTheCompletedSignalCandleNotTheFormingBar() {
        var alert=alert();var source=history(61);
        var closed=source.get(59);var forming=source.get(60);
        source.set(59,new Candle("TEST",closed.timestamp(),closed.open(),closed.high(),closed.low(),closed.close(),17));
        source.set(60,new Candle("TEST",forming.timestamp(),forming.open(),forming.high(),forming.low(),forming.close(),999999));
        when(bars.getBars("TEST","M1",100)).thenReturn(source);
        var contract=new OptionContract("TEST","AAPL",java.time.LocalDate.of(2026,10,16),OptionContract.Type.CALL,BigDecimal.TEN,OptionContract.Moneyness.ATM);
        var volumes=new java.util.HashMap<String,Long>();
        var hit=ReflectionTestUtils.invokeMethod(alert,"evaluateContract",contract,"M1","2026-10-16",props.alerts().optionVolSt(),start.plusSeconds(3600),volumes);
        assertNotNull(hit);assertEquals(17L,volumes.get("TEST"));
        verify(bars,times(1)).getBars("TEST","M1",100);
    }
}
