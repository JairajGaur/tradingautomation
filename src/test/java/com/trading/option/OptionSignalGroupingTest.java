package com.trading.option;

import com.trading.alert.AlertHit;
import com.trading.config.AppProperties;
import com.trading.api.TimeFormat;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionSignalGroupingTest {
    private static final Instant START = Instant.parse("2026-10-16T15:00:00Z");
    private OptionVolumeStAlert.ContractReplay contract(String occ, String side, String strike, int... minutes) {
        var signals = java.util.Arrays.stream(minutes).mapToObj(minute -> new OptionVolumeStAlert.ReplaySignal(
                occ, side, strike, "ATM", TimeFormat.toEt(START.plusSeconds(minute * 60L - 60)),
                "2.00", 3, true, true, true, TimeFormat.toEt(START.plusSeconds(minute * 60L)))).toList();
        return new OptionVolumeStAlert.ContractReplay(occ, side, strike, "ATM", 390, signals);
    }
    @Test void replayCollectsAllStrikesThenAppliesSharedCooldownAtEvaluationTime() {
        var result = OptionSignalGrouping.replay("SPY", List.of(
                contract("put770", "PUT", "770", 0, 30),
                contract("put768", "PUT", "768", 0, 1, 29, 30),
                contract("call770", "CALL", "770", 1)), 30);
        assertEquals(3, result.groups().size());
        assertEquals(7, result.rawContractSignals());
        assertEquals(2, result.cooldownSuppressedGroups());
        assertEquals(List.of("768", "770"), result.groups().get(0).candidates().stream().map(OptionVolumeStAlert.ReplaySignal::strike).toList());
        assertEquals("CALL", result.groups().get(1).type());
        assertEquals(TimeFormat.toEt(START.plusSeconds(1800)), result.groups().get(2).evaluatedAtEt());
        assertEquals(5, result.contracts().stream().mapToInt(c -> c.signals().size()).sum());
    }
    @Test void zeroCooldownStillGroupsSimultaneousStrikes() {
        var result = OptionSignalGrouping.replay("SPY", List.of(contract("a", "PUT", "770", 0, 1), contract("b", "PUT", "768", 0)), 0);
        assertEquals(2, result.groups().size());
        assertEquals(2, result.groups().get(0).candidates().size());
    }
    @Test void liveEmaLatchStartsOnDeliveryAttemptAndCannotBeBypassedByTimeOrStrike() {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.alerts().optionVolSt().cooldownMinutes()).thenReturn(30);
        when(props.alerts().optionVolSt().timeframe()).thenReturn("M1");
        var alert = new OptionVolumeStAlert(props, null, null, null, null, null);
        alert.latch("SPY").observe(START.minusSeconds(60), -1);
        var low = hit("SPY261016P00768000");
        var high = hit("SPY261016P00770000");
        var groups = alert.groupHits("SPY", List.of(high, low), START);
        assertEquals(1, groups.size());
        assertEquals("SPY", groups.get(0).ticker());
        assertNull(groups.get(0).price());
        assertEquals("SPY|PUT", alert.dedupeKey(groups.get(0)));
        assertTrue(groups.get(0).message().contains(low.ticker()));
        assertTrue(groups.get(0).message().contains(high.ticker()));
        assertEquals(1, alert.groupHits("SPY", List.of(high), START.plusSeconds(60)).size());
        alert.onDeliveryAttempted(groups.get(0));
        assertTrue(alert.groupHits("SPY", List.of(high), START.plusSeconds(60)).isEmpty());
        assertTrue(alert.groupHits("SPY", List.of(), START.plusSeconds(120)).isEmpty());
        assertTrue(alert.groupHits("SPY", List.of(low), START.plusSeconds(1740)).isEmpty());
        assertTrue(alert.groupHits("SPY", List.of(high), START.plusSeconds(1800)).isEmpty());
        alert.latch("SPY").observe(START.plusSeconds(1800), 1);
        assertEquals(1, alert.groupHits("SPY", List.of(hit("SPY261016C00770000")), START.plusSeconds(60)).size());
        alert.latch("QQQ").observe(START, -1);
        assertEquals(1, alert.groupHits("QQQ", List.of(hit("QQQ261016P00600000")), START.plusSeconds(60)).size());
    }
    private OptionVolumeStAlert.ContractReplay rankedContract(String occ,String strike,long volume,int... minutes) {
        var signals=java.util.Arrays.stream(minutes).mapToObj(minute->new OptionVolumeStAlert.ReplaySignal(
                occ,"CALL",strike,"ATM",TimeFormat.toEt(START.plusSeconds(minute*60L-60)),"2.00",3,true,true,true,
                TimeFormat.toEt(START.plusSeconds(minute*60L)),999999L,volume,volume,null)).toList();
        return new OptionVolumeStAlert.ContractReplay(occ,"CALL",strike,"ATM",390,signals);
    }
    @Test void replaySelectsOneUsingCandleVolumeAndKeepsStockSideCooldown() {
        var cfg=new AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ONE);
        var result=OptionSignalGrouping.replay("SPY",List.of(rankedContract("a","100",100L,0,1),
                rankedContract("b","102.5",200L,0,1),rankedContract("far","105",9999L,0,1)),30,cfg,new BigDecimal("2.5"),at->new BigDecimal("100"));
        assertEquals(1,result.groups().size());assertEquals(1,result.groups().get(0).candidates().size());
        assertEquals("b",result.groups().get(0).candidates().get(0).occSymbol());
        assertEquals(6,result.rawContractSignals());assertEquals(1,result.cooldownSuppressedGroups());
        assertEquals(1,result.contracts().stream().mapToInt(c->c.signals().size()).sum());
        assertNotNull(result.groups().get(0).selection());
    }
    @Test void missingRankingReferenceDoesNotConsumeCooldownAndCanRecoverNextMinute() {
        var cfg=new AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ONE);
        var result=OptionSignalGrouping.replay("SPY",List.of(rankedContract("a","100",100L,0,1)),30,cfg,
                new BigDecimal("2.5"),at->at.equals(START) ? null : new BigDecimal("100"));
        assertEquals(1,result.rankingSkippedGroups());assertEquals(1,result.groups().size());
        assertEquals(TimeFormat.toEt(START.plusSeconds(60)),result.groups().get(0).evaluatedAtEt());
    }
    @Test void liveAndReplayPickSameContractAndLiveMessageContainsOnlyWinner() {
        var props=mock(AppProperties.class,RETURNS_DEEP_STUBS);
        var cfg=new AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ONE);
        when(props.alerts().optionVolSt().contractRanking()).thenReturn(cfg);
        when(props.alerts().optionVolSt().cooldownMinutes()).thenReturn(30);
        var alert=new OptionVolumeStAlert(props,null,null,null,null,null);
        alert.latch("SPY").observe(START.minusSeconds(60), 1);
        var a=hit("SPY261019C00100000");var b=hit("SPY261019C00102500");var far=hit("SPY261019C00105000");
        var volumes=java.util.Map.of(a.ticker(),100L,b.ticker(),200L,far.ticker(),9999L);
        var group=alert.groupHits("SPY",List.of(a,b,far),START,new BigDecimal("100"),new BigDecimal("2.5"),volumes).get(0);
        assertTrue(group.message().contains(b.ticker()));assertFalse(group.message().contains(a.ticker()));assertFalse(group.message().contains(far.ticker()));
        assertTrue(group.message().contains("Signal candle volume: 200"));
        alert.onDeliveryAttempted(group);
        assertTrue(alert.groupHits("SPY",List.of(a),START.plusSeconds(60),new BigDecimal("100"),new BigDecimal("2.5"),volumes).isEmpty());
        var replay=OptionSignalGrouping.replay("SPY",List.of(rankedContract(a.ticker(),"100",100L,0),rankedContract(b.ticker(),"102.5",200L,0),
                rankedContract(far.ticker(),"105",9999L,0)),30,cfg,new BigDecimal("2.5"),at->new BigDecimal("100"));
        assertEquals(b.ticker(),replay.groups().get(0).selection().occSymbol());
    }
    private AlertHit hit(String occ) { return new AlertHit("option-vol-st", occ, "test", "test", null, new BigDecimal("2.00"), "M1"); }
}
