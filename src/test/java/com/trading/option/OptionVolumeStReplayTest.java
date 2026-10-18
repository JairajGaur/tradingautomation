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

    private OptionVolumeStAlert alert(boolean m3, boolean volume, int cooldown) {
        var cfg = props.alerts().optionVolSt();
        when(cfg.timeframe()).thenReturn("M1");
        when(cfg.stRisingCandles()).thenReturn(1);
        when(cfg.emaPeriod()).thenReturn(3);
        when(cfg.emaAboveCandles()).thenReturn(1);
        when(cfg.threeMinStEnabled()).thenReturn(m3);
        when(cfg.threeMinStTimeframe()).thenReturn("M3");
        when(cfg.volumeEnabled()).thenReturn(volume);
        when(cfg.minSessionVolume()).thenReturn(100_000L);
        when(cfg.cooldownMinutes()).thenReturn(cooldown);
        when(props.supertrend().length()).thenReturn(3);
        when(props.supertrend().factor()).thenReturn(BigDecimal.ONE);
        return new OptionVolumeStAlert(props, null, null, null, new SupertrendService(props));
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
