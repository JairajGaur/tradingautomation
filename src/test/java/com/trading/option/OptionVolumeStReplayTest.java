package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionVolumeStReplayTest {
    private final LocalDate date = LocalDate.of(2026, 10, 16);
    private final OptionContract contract = OptionContract.of("AAPL", date,
            OptionContract.Type.CALL, BigDecimal.valueOf(250), OptionContract.Moneyness.ATM);
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final com.trading.service.BarDataManager stockBars = mock(com.trading.service.BarDataManager.class);
    private final OptionHaDirectionFilter haFilter = mock(OptionHaDirectionFilter.class);

    private OptionVolumeStAlert alert(boolean m3, boolean volume, int cooldown) {
        var cfg = props.alerts().optionVolSt();
        when(cfg.optionStEnabled()).thenReturn(true);
        when(cfg.stRisingEnabled()).thenReturn(true);
        when(cfg.emaAboveEnabled()).thenReturn(true);

        when(cfg.timeframe()).thenReturn("M1");
        when(cfg.stRisingCandles()).thenReturn(1);
        when(cfg.emaPeriod()).thenReturn(3);
        when(cfg.emaAboveCandles()).thenReturn(1);
        when(cfg.stockHigherStEnabled()).thenReturn(m3);
        when(cfg.stockHigherStTimeframe()).thenReturn("M3");
        when(cfg.volumeEnabled()).thenReturn(volume);
        when(cfg.minSessionVolume()).thenReturn(100_000L);
        when(cfg.cooldownMinutes()).thenReturn(cooldown);
        when(stockBars.getBars(eq("AAPL"), anyString(), anyInt())).thenAnswer(invocation -> {
            int minutes = com.trading.service.TimeframeSupport.minutesOf(invocation.getArgument(1));
            var history = bars(Instant.parse("2026-10-16T13:30:00Z"), 390);
            return java.util.stream.IntStream.range(0,390/minutes).mapToObj(i -> history.get(i*minutes)).toList();
        });
        when(props.supertrend().length()).thenReturn(3);
        when(props.supertrend().factor()).thenReturn(BigDecimal.ONE);
        return new OptionVolumeStAlert(props, null, null, null, new SupertrendService(props), haFilter, null, new OptionStockTrendFilter(props, stockBars));
    }

    private List<Candle> bars(Instant start, int count) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal p = BigDecimal.valueOf(10 + i);
            out.add(new Candle(contract.occSymbol(), start.plusSeconds(i * 60L),
                    p, p.add(BigDecimal.ONE), p.subtract(BigDecimal.ONE), p, 100));
        }
        return out;
    }

    @Test
    void replaySupportsMultipleEmaAndHigherStConfirmationsWithoutFutureBars() {
        var alert=alert(true,false,0);
        var c=props.alerts().optionVolSt();
        when(c.stockEmaStackEnabled()).thenReturn(true);
        when(c.stockEmaStackFast()).thenReturn(2);
        when(c.stockEmaStackSlow()).thenReturn(3);
        when(c.stockEmaStackTimeframe()).thenReturn("M1,M5");
        when(c.stockHigherStTimeframe()).thenReturn("M3,M5");
        var source=bars(Instant.parse("2026-10-16T13:30:00Z"),120);
        when(stockBars.getBars(eq("AAPL"), anyString(), anyInt())).thenAnswer(invocation -> {
            String tf = invocation.getArgument(1);
            int minutes = com.trading.service.TimeframeSupport.minutesOf(tf);
            return java.util.stream.IntStream.range(0,120/minutes).mapToObj(i -> source.get(i*minutes)).toList();
        });
        var prefix=alert.replayCandidates(contract,source.subList(0,90),date).signals();
        var complete=alert.replayCandidates(contract,source,date).signals();
        assertFalse(prefix.isEmpty());
        assertEquals(prefix,complete.subList(0,prefix.size()));
    }

    @Test
    void risingPutPremiumCanQualifyWithBearishStockConfirmations() {
        var alert=alert(true,false,0);
        var c=props.alerts().optionVolSt();
        when(c.stockEmaStackEnabled()).thenReturn(true);
        when(c.stockEmaStackFast()).thenReturn(2);
        when(c.stockEmaStackSlow()).thenReturn(3);
        when(c.stockEmaStackTimeframe()).thenReturn("M1,M5");
        var start=Instant.parse("2026-10-16T13:30:00Z");
        when(stockBars.getBars(eq("AAPL"),anyString(),anyInt())).thenAnswer(invocation -> {
            int minutes=com.trading.service.TimeframeSupport.minutesOf(invocation.getArgument(1));
            return java.util.stream.IntStream.range(0,120/minutes).mapToObj(i -> {
                var p=BigDecimal.valueOf(500-i*minutes);
                return new Candle("AAPL",start.plusSeconds(i*minutes*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100);
            }).toList();
        });
        var put=OptionContract.of("AAPL",date,OptionContract.Type.PUT,BigDecimal.valueOf(250),OptionContract.Moneyness.ATM);
        var premiums=bars(start,120);
        assertFalse(alert.replayCandidates(put,premiums,date).signals().isEmpty());
        assertTrue(alert.replayCandidates(contract,premiums,date).signals().isEmpty());
        // Live and inspection use the identical stock-side rules while premiums trend UP.
        var optionBars=mock(OptionBarDataManager.class);
        when(optionBars.getBars(anyString(),eq("M1"),anyInt())).thenReturn(premiums);
        var live=new OptionVolumeStAlert(props,null,null,optionBars,new SupertrendService(props),haFilter,null,
                new OptionStockTrendFilter(props,stockBars));
        var asOf=start.plusSeconds(119*60L);
        var inspected=(OptionVolumeStAlert.LegEval)org.springframework.test.util.ReflectionTestUtils.invokeMethod(live,"evaluateLegs",put,asOf);
        assertTrue(inspected.qualifies());
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(live,"evaluateContract",put,"M1",date.toString(),c,asOf));
        assertNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(live,"evaluateContract",contract,"M1",date.toString(),c,asOf));
        verify(stockBars,atLeastOnce()).getBars(eq("AAPL"),eq("M5"),anyInt());
    }

    @Test
    void reportVolumesUseCompletedSessionBarsWithoutFutureOrPriorSessionVolume() {
        var alert = alert(false, false, 0);
        var start = Instant.parse("2026-10-16T13:30:00Z");
        var input = new ArrayList<>(bars(start.minusSeconds(86400), 30));
        input.addAll(bars(start, 70));
        var finalBar = input.get(input.size() - 1);
        input.set(input.size() - 1, new Candle(finalBar.ticker(), finalBar.timestamp(), finalBar.open(),
                finalBar.high(), finalBar.low(), finalBar.close(), 999999));
        var signals = alert.replayCandidates(contract, input, date).signals();
        assertFalse(signals.isEmpty());
        for (var signal : signals) {
            long count = java.time.Duration.between(start, OptionSignalGrouping.instant(signal.firedAtEt())).toMinutes() + 1;
            assertEquals(count * 100, signal.sessionVolume());
            assertEquals(100L, signal.signalBarVolume());
            assertEquals(Math.min(5, count) * 100, signal.recentFiveBarVolume());
        }
    }

    @Test
    void replayRoutesCallsAndPutsByCompletedUnderlyingHaColor() {
        var alert = alert(false, false, 0);
        when(props.alerts().optionVolSt().haDirectionEnabled()).thenReturn(true);
        Instant start = Instant.parse("2026-10-16T13:30:00Z");
        Candle green = new Candle("AAPL", start, BigDecimal.TEN, BigDecimal.valueOf(16),
                BigDecimal.TEN, BigDecimal.valueOf(12), 100);
        Candle red = new Candle("AAPL", start.plusSeconds(900), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, 100);
        when(haFilter.load("AAPL")).thenReturn(new OptionHaDirectionFilter.Series(List.of(green, red), 15));
        var optionBars = bars(start, 60);
        var calls = alert.replayContract(contract, optionBars, date).signals();
        var put = OptionContract.of("AAPL", date, OptionContract.Type.PUT,
                BigDecimal.valueOf(250), OptionContract.Moneyness.ATM);
        var puts = alert.replayContract(put, optionBars, date).signals();
        assertEquals(15, calls.size());
        assertEquals(15, puts.size());
        assertEquals(com.trading.api.TimeFormat.toEt(start.plusSeconds(14 * 60)), calls.get(0).firedAtEt());
        assertEquals(com.trading.api.TimeFormat.toEt(start.plusSeconds(29 * 60)), puts.get(0).firedAtEt());
    }

    @Test
    void futureConflictingPrintCannotRemoveEarlierReplaySignals() {
        var alert = alert(false, false, 0);
        var start = Instant.parse("2026-10-16T13:30:00Z");
        var complete = bars(start, 90);
        var baseline = alert.replayContract(contract, complete, date);
        var conflicted = new ArrayList<>(complete);
        var future = complete.get(80);
        conflicted.add(new Candle(future.ticker(), future.timestamp(), future.open(), future.high(),
                future.low(), future.close(), 999));
        var replay = alert.replayContract(contract, conflicted, date);
        var cutoff = com.trading.api.TimeFormat.toEt(start.plusSeconds(80 * 60));
        assertEquals(baseline.signals().stream().filter(s -> s.firedAtEt().compareTo(cutoff) < 0).toList(),
                replay.signals().stream().filter(s -> s.firedAtEt().compareTo(cutoff) < 0).toList());
        assertTrue(replay.dataQualityRejections().containsKey("CONFLICTING_DUPLICATE"));
    }

    @Test
    void replayReportsHistoricalGapsWithoutChangingEarlierSignals() {
        var alert = alert(false, false, 0);
        when(props.alerts().optionVolSt().barQuality().gapWindow()).thenReturn(20);
        var start = Instant.parse("2026-10-16T13:30:00Z");
        var complete = bars(start, 90);
        var baseline = alert.replayContract(contract, complete, date);
        var missing = new ArrayList<>(complete);
        missing.remove(50);
        missing.remove(50);
        var replay = alert.replayContract(contract, missing, date);
        assertTrue(replay.dataQualityRejections().containsKey("EXCESSIVE_GAPS"));
        var cutoff = com.trading.api.TimeFormat.toEt(start.plusSeconds(49 * 60));
        assertEquals(baseline.signals().stream().filter(s -> s.firedAtEt().compareTo(cutoff) < 0).toList(),
                replay.signals().stream().filter(s -> s.firedAtEt().compareTo(cutoff) < 0).toList());
    }

    @Test
    void replayHonorsAtrCapAndDisableSwitch() {
        var alert = alert(false, false, 0);
        var source = bars(Instant.parse("2026-10-16T13:30:00Z"), 90);
        var baseline = alert.replayContract(contract, source, date).signals();
        assertFalse(baseline.isEmpty());
        when(props.alerts().optionVolSt().largeCandleFilterEnabled()).thenReturn(true);
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(new BigDecimal("0.5"));
        var rejected = alert.replayContract(contract, source, date);
        assertTrue(rejected.signals().isEmpty());
        assertTrue(rejected.largeCandleRejections() > 0);
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(new BigDecimal("3"));
        assertEquals(baseline, alert.replayContract(contract, source, date).signals());
        when(props.alerts().optionVolSt().largeCandleFilterEnabled()).thenReturn(false);
        when(props.alerts().optionVolSt().maxCandleAtr()).thenReturn(new BigDecimal("0.5"));
        assertEquals(baseline, alert.replayContract(contract, source, date).signals());
    }

    @Test
    void replayProducesSignalsAndHonorsM3Gate() {
        var bars = bars(Instant.parse("2026-10-16T13:30:00Z"), 90);
        var base = alert(false, false, 0).replayContract(contract, bars, date);
        var filtered = alert(true, false, 0).replayContract(contract, bars, date);
        assertFalse(base.signals().isEmpty());
        assertFalse(filtered.signals().isEmpty());
        assertTrue(filtered.signals().size() < base.signals().size());
        assertTrue(base.signals().containsAll(filtered.signals()));
    }

    @Test
    void formingCandleFinalVolumeCannotTriggerHistoricalSignal() {
        var bars = bars(Instant.parse("2026-10-16T13:30:00Z"), 60);
        var last = bars.get(59);
        bars.set(59, new Candle(last.ticker(), last.timestamp(), last.open(), last.high(),
                last.low(), last.close(), 1_000_000));
        assertTrue(alert(false, true, 0).replayContract(contract, bars, date).signals().isEmpty());
    }

    @Test
    void cooldownRearmsAtExactBoundary() {
        var result = alert(false, false, 5).replayContract(contract,
                bars(Instant.parse("2026-10-16T13:30:00Z"), 60), date);
        assertTrue(result.signals().size() > 2);
        var times = result.signals().stream().map(s -> s.firedAtEt()).toList();
        // Every eligible bar qualifies in this rising series: five-minute cooldown gives
        // the same count as selecting every fifth signal from the no-cooldown replay.
        var everyMinute = alert(false, false, 0).replayContract(contract,
                bars(Instant.parse("2026-10-16T13:30:00Z"), 60), date).signals();
        for (int i = 0; i < times.size(); i++) {
            assertEquals(everyMinute.get(i * 5).firedAtEt(), times.get(i));
        }
    }

    @Test
    void replayExcludesOtherDatesAndSessionClose() {
        var alert = alert(false, false, 0);
        var previousDay = bars(Instant.parse("2026-10-15T13:30:00Z"), 60);
        assertTrue(alert.replayContract(contract, previousDay, date).signals().isEmpty());
        var closing = alert.replayContract(contract,
                bars(Instant.parse("2026-10-16T19:00:00Z"), 90), date);
        assertEquals(59, closing.rthBars());
        assertTrue(alert.replayContract(contract, List.of(), date).signals().isEmpty());
    }
}
