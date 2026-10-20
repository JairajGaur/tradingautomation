package com.trading.option;

import com.trading.model.Candle;
import com.trading.service.BarQuality;
import com.trading.api.TimeFormat;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OptionEmaLatchTest {
    private static final Instant START = Instant.parse("2026-10-19T13:30:00Z");
    static OptionStockTrendFilter.Series series() {
        // Bullish, bearish, bullish; no option signal is needed during the bearish interval.
        double[] prices={100,101,102,103,104,80,79,78,110,111,112};
        List<Candle> candles=new ArrayList<>();
        for(int i=0;i<prices.length;i++) {
            var p=BigDecimal.valueOf(prices[i]);
            candles.add(new Candle("QQQ",START.plusSeconds(i*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100));
        }
        return new OptionStockTrendFilter.Series(List.of(new OptionStockTrendFilter.Frame(candles,1,1,3,
                new BarQuality.Policy(1,2,20,0))),true,2,3);
    }
    @Test void timeDoesNotRearmButEqualityAndRecoveryDoForBothSides() {
        var latch=new OptionEmaLatch();
        latch.observe(START,1);latch.consume("CALL",latch.generation());
        latch.observe(START.plusSeconds(3600),1);
        assertFalse(latch.allows("CALL"));assertFalse(latch.allows("PUT"));
        latch.observe(START.plusSeconds(3660),0);
        assertFalse(latch.allows("CALL"));assertFalse(latch.allows("PUT"));
        latch.observe(START.plusSeconds(3720),1);assertTrue(latch.allows("CALL"));
        latch.observe(START.plusSeconds(3780),-1);assertTrue(latch.allows("PUT"));
        latch.consume("PUT",latch.generation());latch.observe(START.plusSeconds(3840),-1);
        assertFalse(latch.allows("PUT"));
        latch.observe(START.plusSeconds(3900),1);latch.observe(START.plusSeconds(3960),-1);
        assertTrue(latch.allows("PUT"));
    }
    @Test void missingDataDoesNotRearmAndFormingFutureCandlesCannotChangeState() {
        var latch=new OptionEmaLatch();var series=series();
        latch.update(series,START.plusSeconds(5*60));assertTrue(latch.allows("CALL"));
        long episode=latch.generation();latch.consume("CALL",episode);
        latch.update(null,START.plusSeconds(5*60));assertFalse(latch.allows("CALL"));
        latch.update(series,START.plusSeconds(5*60+30));
        assertEquals(episode,latch.generation());assertFalse(latch.allows("CALL"));
        latch.update(series,START.plusSeconds(3600));assertFalse(latch.allows("CALL"));
        assertEquals(episode,latch.generation());
    }
    @Test void observesInterveningBreaksWithoutOptionCandidatesAndRejectsOldDelivery() {
        var latch=new OptionEmaLatch();var series=series();
        latch.update(series,START.plusSeconds(5*60));long old=latch.generation();latch.consume("CALL",old);
        latch.update(series,START.plusSeconds(11*60));
        assertTrue(latch.allows("CALL"));assertTrue(latch.generation()>old);
        latch.consume("CALL",old);assertTrue(latch.allows("CALL"));
        latch.consume("CALL",latch.generation());assertFalse(latch.allows("CALL"));
    }
    @Test void lowestIntervalIsChosenRegardlessOfConfigurationOrder() {
        var low=series();var high=new OptionStockTrendFilter.Frame(List.of(),5,5,3,new BarQuality.Policy(1,2,20,0));
        var latch=new OptionEmaLatch();
        latch.update(new OptionStockTrendFilter.Series(List.of(high,low.frames().get(0)),true,2,3),START.plusSeconds(300));
        assertTrue(latch.allows("CALL"));
    }
    private OptionVolumeStAlert.ContractReplay raw(String side,int... minutes) {
        var signals=Arrays.stream(minutes).mapToObj(i->new OptionVolumeStAlert.ReplaySignal(
                side,side,"100","ATM",TimeFormat.toEt(START.plusSeconds((i-1)*60L)),"2",1,true,true,true,
                TimeFormat.toEt(START.plusSeconds(i*60L)))).toList();
        return new OptionVolumeStAlert.ContractReplay(side,side,"100","ATM",390,signals);
    }
    @Test void productionReplayRearmsOnEmaBreakBetweenCandidateTimes() {
        var raw=List.of(raw("CALL",4,5,10,11),raw("PUT",7,8));
        var result=OptionSignalGrouping.replayByEma("QQQ",raw,null,null,at->null,series());
        assertEquals(List.of("CALL","PUT","CALL"),result.groups().stream().map(OptionSignalGrouping.Group::type).toList());
        assertEquals(3,result.cooldownSuppressedGroups());
        var live=new OptionEmaLatch();List<String> accepted=new ArrayList<>();
        for(int minute:new int[]{4,5,7,8,10,11}) {
            live.update(series(),START.plusSeconds(minute*60L));String side=minute==7||minute==8?"PUT":"CALL";
            if(live.allows(side)){accepted.add(side);live.consume(side,live.generation());}
        }
        assertEquals(result.groups().stream().map(OptionSignalGrouping.Group::type).toList(),accepted);
    }
    @Test void failedRankingDoesNotConsumeEmaEpisode() {
        var cfg=new com.trading.config.AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ONE);
        var signals=new ArrayList<OptionVolumeStAlert.ReplaySignal>();
        for(int minute:new int[]{4,5}) signals.add(new OptionVolumeStAlert.ReplaySignal(
                "QQQ261020C00100000","CALL","100","ATM",TimeFormat.toEt(START.plusSeconds((minute-1)*60L)),
                "2",1,true,true,true,TimeFormat.toEt(START.plusSeconds(minute*60L)),100L,10L,10L,null));
        var contract=new OptionVolumeStAlert.ContractReplay("QQQ261020C00100000","CALL","100","ATM",390,signals);
        var result=OptionSignalGrouping.replayByEma("QQQ",List.of(contract),cfg,BigDecimal.ONE,
                at->at.equals(START.plusSeconds(240))?null:BigDecimal.valueOf(100),series());
        assertEquals(1,result.rankingSkippedGroups());assertEquals(1,result.groups().size());
        assertEquals(TimeFormat.toEt(START.plusSeconds(300)),result.groups().get(0).evaluatedAtEt());
    }
    @Test void unavailableLowestEmaFailsClosed() {
        assertTrue(OptionSignalGrouping.replayByEma("QQQ",List.of(raw("CALL",4,5)),null,null,at->null,null).groups().isEmpty());
    }
}
