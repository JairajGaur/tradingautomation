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

class OptionEmaTimeframeTest {
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final Instant start = Instant.parse("2026-10-16T13:30:00Z");
    private OptionVolumeStAlert alert(String source, String target) {
        when(props.alerts().optionVolSt().timeframe()).thenReturn(source);
        when(props.alerts().optionVolSt().emaStackTimeframe()).thenReturn(target);
        when(props.alerts().optionVolSt().emaStackFast()).thenReturn(2);
        when(props.alerts().optionVolSt().emaStackSlow()).thenReturn(3);
        when(props.alerts().optionVolSt().barQuality().maxAgeBars()).thenReturn(1);
        return new OptionVolumeStAlert(props,null,null,null,null,null);
    }
    private List<Candle> bars(int count, boolean rising) {
        List<Candle> bars = new ArrayList<>();
        for(int i=0;i<count;i++) {
            BigDecimal p=BigDecimal.valueOf(rising ? 100+i : 100-i);
            bars.add(new Candle("AAPL",start.plusSeconds(i*60L),p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,100));
        }
        return bars;
    }
    private boolean passes(OptionVolumeStAlert alert,List<Candle> bars,Instant asOf) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(alert,"emaStackUpAt",bars,asOf));
    }
    @Test void configurableFiveAndFifteenMinuteEmaUseCompletedAggregatedCandles() {
        assertTrue(passes(alert("M1","M5"),bars(60,true),start.plusSeconds(3600)));
        assertTrue(passes(alert("M1","M15"),bars(60,true),start.plusSeconds(3600)));
        assertFalse(passes(alert("M1","M5"),bars(60,false),start.plusSeconds(3600)));
    }
    @Test void formingBucketCannotReverseTheCompletedEmaCondition() {
        var alert=alert("M1","M5");
        var bars=bars(60,false);
        for(int i=60;i<64;i++) {
            var p=BigDecimal.valueOf(10000);
            bars.add(new Candle("AAPL",start.plusSeconds(i*60L),p,p,p,p,100));
        }
        assertFalse(passes(alert,bars,start.plusSeconds(64*60L)));
    }
    @Test void missingSourceBarsWarmupAndInvalidTimeframeCannotConfirmTrend() {
        var alert=alert("M1","M5");
        assertFalse(passes(alert,bars(10,true),start.plusSeconds(600)));
        var gap=bars(20,true);gap.remove(13);
        assertFalse(passes(alert,gap,start.plusSeconds(1200)));
        assertFalse(passes(alert("M5","M3"),bars(60,true),start.plusSeconds(3600)));
    }
    @Test void blankSettingPreservesBaseTimeframe() {
        var alert=alert("M1",null);
        assertEquals("M1",alert.emaStackTimeframe());
        assertTrue(passes(alert,bars(20,true),start.plusSeconds(1200)));
    }
}
