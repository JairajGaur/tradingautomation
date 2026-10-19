package com.trading.indicator;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyPivotCalculatorTest {
    private final LocalDate friday=LocalDate.of(2026,10,16), monday=LocalDate.of(2026,10,19);
    private final ZoneId et=ZoneId.of("America/New_York");
    private List<Candle> session(LocalDate day,int count,int price) {
        var bars=new ArrayList<Candle>();
        for(int i=0;i<count;i++) bars.add(new Candle("AAPL",day.atTime(9,30).atZone(et).toInstant().plusSeconds(i*60L),
                BigDecimal.valueOf(price),BigDecimal.valueOf(price+10),BigDecimal.valueOf(price-10),BigDecimal.valueOf(price),100));
        return bars;
    }
    @Test void standardFormulasUsePreviousFridayAndExcludeCurrentAndExtendedHours() {
        var bars=session(friday,390,100);bars.addAll(session(monday,30,1000));
        bars.add(new Candle("AAPL",friday.atTime(16,0).atZone(et).toInstant(),BigDecimal.ONE,BigDecimal.valueOf(9999),BigDecimal.ONE,BigDecimal.ONE,100));
        var result=DailyPivotCalculator.calculate("AAPL",bars,monday,monday.atTime(10,0).atZone(et).toInstant());
        assertTrue(result.available());assertEquals(friday,result.sourceSession());
        assertEquals(new BigDecimal("100.0000"),result.pivot());assertEquals(new BigDecimal("110.0000"),result.r1());
        assertEquals(new BigDecimal("90.0000"),result.s1());assertEquals(new BigDecimal("120.0000"),result.r2());assertEquals(new BigDecimal("80.0000"),result.s2());
    }
    @Test void incompleteLatestSessionDoesNotFallBackToOlderCompleteSession() {
        var bars=session(friday.minusDays(1),390,90);bars.addAll(session(friday,389,100));
        var result=DailyPivotCalculator.calculate("AAPL",bars,monday,monday.atStartOfDay(et).toInstant());
        assertFalse(result.available());assertEquals(friday,result.sourceSession());
    }
    @Test void missingConflictingOrEarlyCloseBarsAreExplicitlyUnavailable() {
        var now=monday.atStartOfDay(et).toInstant();
        var gap=session(friday,390,100);gap.remove(190);
        assertFalse(DailyPivotCalculator.calculate("AAPL",gap,monday,now).available());
        var conflict=session(friday,390,100);var c=conflict.get(50);
        conflict.add(new Candle("AAPL",c.timestamp(),c.open(),c.high(),c.low(),c.close(),999));
        assertFalse(DailyPivotCalculator.calculate("AAPL",conflict,monday,now).available());
        assertFalse(DailyPivotCalculator.calculate("AAPL",session(friday,210,100),monday,now).available());
    }
    @Test void historicalCalculationIsUnaffectedByFutureConflictingData() {
        var bars=session(friday.minusDays(1),390,100);var asOf=friday.atTime(9,30).atZone(et).toInstant();
        var before=DailyPivotCalculator.calculate("AAPL",bars,friday,asOf);
        bars.addAll(session(friday,390,500));bars.addAll(session(monday,390,1000));
        assertEquals(before,DailyPivotCalculator.calculate("AAPL",bars,friday,asOf));
    }
    @Test void nearestLevelsUseCompletedStockCloseAndNeverStalePrices() {
        var bars=session(friday,390,100);bars.addAll(session(monday,30,105));
        var now=monday.atTime(10,0).atZone(et).toInstant();
        var levels=DailyPivotCalculator.calculate("AAPL",bars,monday,now);
        var context=DailyPivotCalculator.context(levels,bars,now);
        assertEquals("P",context.get("nearestLowerLevel"));assertEquals("R1",context.get("nearestUpperLevel"));
        assertEquals(new BigDecimal("5.0000"),context.get("distanceToLower"));
        assertFalse(DailyPivotCalculator.context(levels,bars,now.plusSeconds(3600)).containsKey("stockPrice"));
    }
}
