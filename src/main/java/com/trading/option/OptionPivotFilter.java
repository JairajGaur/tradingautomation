package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.indicator.DailyPivotCalculator;
import com.trading.indicator.SupertrendCalculator;
import com.trading.model.Candle;
import com.trading.service.BarQuality;
import com.trading.service.TimeframeSupport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Stock-only entry-location check. Pivots are previous RTH; price/ATR use completed RTH bars. */
public final class OptionPivotFilter {
    private static final ZoneId ET=ZoneId.of("America/New_York");
    private OptionPivotFilter() {}
    public record Decision(boolean enabled, boolean passes, String reason, String pivotBias,
                           BigDecimal stockPrice, BigDecimal stockAtr, String nextLevel,
                           BigDecimal nextLevelPrice, BigDecimal distance, BigDecimal roomAtr,
                           LocalDate pivotSourceSession) {}
    public static boolean enabled(AppProperties.Alerts.PivotFilterCfg cfg) { return cfg!=null && cfg.enabled(); }
    public static Decision disabled() { return new Decision(false,true,"DISABLED",null,null,null,null,null,null,null,null); }
    public static Decision unavailable(String reason) { return new Decision(true,false,reason,null,null,null,null,null,null,null,null); }

    /** Immutable replay snapshot; calculations below only consume candles completed at asOf. */
    public record Series(String symbol, List<Candle> history, LocalDate session,
                         AppProperties.Alerts.PivotFilterCfg cfg,
                         AppProperties.Alerts.BarQualityCfg qualityCfg) {
        public Decision at(OptionContract.Type side, Instant asOf) {
            if (!enabled(cfg)) return disabled();
            int minutes=TimeframeSupport.minutesOf(cfg.atrTimeframe());
            if (minutes<1 || cfg.atrPeriod()<1 || cfg.minRoomAtr()==null || cfg.minRoomAtr().signum()<0)
                return unavailable("PIVOT_INVALID_CONFIG");
            if (!asOf.atZone(ET).toLocalDate().equals(session)) return unavailable("PIVOT_WRONG_SESSION");
            var levels=DailyPivotCalculator.calculate(symbol,history,session,asOf);
            if (!levels.available()) return unavailable("PIVOT_LEVELS_UNAVAILABLE");
            var policy=BarQuality.Policy.from(qualityCfg);
            var source=history==null ? List.<Candle>of() : history.stream().filter(c -> c!=null
                    && c.ticker().equalsIgnoreCase(symbol)
                    && !c.timestamp().atZone(ET).toLocalTime().isBefore(LocalTime.of(9,30))
                    && c.timestamp().atZone(ET).toLocalTime().isBefore(LocalTime.of(16,0))).toList();
            var priceQuality=BarQuality.assess(source,1,asOf,1,policy);
            if (!priceQuality.valid()) return unavailable("PIVOT_STOCK_"+priceQuality.reason());
            var completed=priceQuality.completed();
            var latest=completed.get(completed.size()-1);
            if (!latest.timestamp().atZone(ET).toLocalDate().equals(session)) return unavailable("PIVOT_STOCK_WRONG_SESSION");
            var atrBars=minutes==1 ? completed : BarQuality.completeAggregates(completed,1,minutes,asOf);
            var atrQuality=BarQuality.assess(atrBars,minutes,asOf,cfg.atrPeriod(),
                    new BarQuality.Policy(policy.maxAgeBars(),policy.graceSeconds(),policy.gapWindow(),0));
            if (!atrQuality.valid()) return unavailable("PIVOT_ATR_"+atrQuality.reason());
            // Do not silently substitute an older ATR bucket when the latest closed bucket is incomplete.
            var rawBuckets=com.trading.service.BarResampler.resample(completed,minutes).stream()
                    .filter(c -> !c.timestamp().plusSeconds(minutes*60L).isAfter(asOf)).toList();
            if (!rawBuckets.isEmpty() && !rawBuckets.get(rawBuckets.size()-1).timestamp().equals(
                    atrQuality.completed().get(atrQuality.completed().size()-1).timestamp()))
                return unavailable("PIVOT_ATR_INCOMPLETE_BUCKET");
            var point=SupertrendCalculator.latest(atrQuality.completed(),cfg.atrPeriod(),BigDecimal.ONE);
            if (point==null || point.atr()==null || point.atr().signum()<=0) return unavailable("PIVOT_ATR_UNAVAILABLE");
            BigDecimal price=latest.close(),atr=point.atr();
            String bias=price.compareTo(levels.pivot())>0 ? "ABOVE_P" : price.compareTo(levels.pivot())<0 ? "BELOW_P" : "AT_P";
            var boundaries=new LinkedHashMap<String,BigDecimal>();
            boundaries.put("S2",levels.s2());boundaries.put("S1",levels.s1());boundaries.put("P",levels.pivot());
            boundaries.put("R1",levels.r1());boundaries.put("R2",levels.r2());
            Map.Entry<String,BigDecimal> next=null;
            for (var level:boundaries.entrySet()) {
                int compare=level.getValue().compareTo(price);
                if (side==OptionContract.Type.CALL ? compare>=0 : compare<=0) {
                    if (next==null || (side==OptionContract.Type.CALL
                            ? level.getValue().compareTo(next.getValue())<0 : level.getValue().compareTo(next.getValue())>0)) next=level;
                }
            }
            if (next==null) return new Decision(true,true,"NO_KNOWN_PIVOT_AHEAD",bias,price,atr,null,null,null,null,levels.sourceSession());
            BigDecimal distance=next.getValue().subtract(price).abs();
            boolean passes=distance.compareTo(atr.multiply(cfg.minRoomAtr()))>=0;
            return new Decision(true,passes,passes ? "PIVOT_ROOM_OK" : "PIVOT_TOO_CLOSE",bias,price,atr,next.getKey(),next.getValue(),
                    distance,distance.divide(atr,6,RoundingMode.HALF_UP),levels.sourceSession());
        }
    }
}
