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
    @Test void liveCooldownStartsOnDeliveryAttemptAndCannotBeBypassedByChangingStrike() {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.alerts().optionVolSt().cooldownMinutes()).thenReturn(30);
        when(props.alerts().optionVolSt().timeframe()).thenReturn("M1");
        var alert = new OptionVolumeStAlert(props, null, null, null, null, null);
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
        assertEquals(1, alert.groupHits("SPY", List.of(high), START.plusSeconds(1800)).size());
        assertEquals(1, alert.groupHits("SPY", List.of(hit("SPY261016C00770000")), START.plusSeconds(60)).size());
        assertEquals(1, alert.groupHits("QQQ", List.of(hit("QQQ261016P00600000")), START.plusSeconds(60)).size());
    }
    private AlertHit hit(String occ) { return new AlertHit("option-vol-st", occ, "test", "test", null, new BigDecimal("2.00"), "M1"); }
}
