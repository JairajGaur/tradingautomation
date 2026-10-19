package com.trading.indicator;

import com.trading.model.Candle;
import com.trading.service.BarQuality;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Standard daily pivots from the preceding complete RTH session, never the current day's range. */
public final class DailyPivotCalculator {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private DailyPivotCalculator() {}
    public record Levels(String underlying, LocalDate sessionDate, LocalDate sourceSession, boolean available,
                         BigDecimal previousHigh, BigDecimal previousLow, BigDecimal previousClose,
                         BigDecimal pivot, BigDecimal r1, BigDecimal s1, BigDecimal r2, BigDecimal s2, String note) {}
    public static Levels unavailable(String symbol, LocalDate session, LocalDate source, String reason) {
        return new Levels(symbol,session,source,false,null,null,null,null,null,null,null,null,reason);
    }
    public static Levels calculate(String symbol, List<Candle> history, LocalDate session, Instant asOf) {
        if (session == null) return unavailable(symbol,null,null,"No reference session");
        LocalDate prior = null;
        List<Candle> eligible = new ArrayList<>();
        if (history != null) for (Candle c : history) {
            if (c == null || !c.ticker().equalsIgnoreCase(symbol)) continue;
            var time = c.timestamp().atZone(ET);
            if (time.toLocalDate().isBefore(session) && isRth(time.toLocalTime())
                    && !c.timestamp().plusSeconds(60).isAfter(asOf)) {
                eligible.add(c);
                if (prior == null || time.toLocalDate().isAfter(prior)) prior = time.toLocalDate();
            }
        }
        if (prior == null) return unavailable(symbol,session,null,"Previous RTH session unavailable");
        LocalDate source = prior;
        var selected = eligible.stream().filter(c -> c.timestamp().atZone(ET).toLocalDate().equals(source)).toList();
        var quality = BarQuality.assess(selected,1,source.atTime(16,0).atZone(ET).toInstant(),390,
                new BarQuality.Policy(1,0,390,0));
        var bars = quality.completed();
        if (!quality.valid() || bars.size()!=390 || !bars.get(0).timestamp().atZone(ET).toLocalTime().equals(LocalTime.of(9,30))
                || !bars.get(389).timestamp().atZone(ET).toLocalTime().equals(LocalTime.of(15,59))) {
            return unavailable(symbol,session,source,"Incomplete prior RTH session: " + (quality.reason()==null?"expected 390 M1 bars":quality.reason())
                    + "; early-close sessions require calendar support");
        }
        BigDecimal high = bars.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = bars.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal close = bars.get(bars.size()-1).close();
        BigDecimal p = high.add(low).add(close).divide(BigDecimal.valueOf(3),12,RoundingMode.HALF_UP);
        BigDecimal range = high.subtract(low);
        return new Levels(symbol,session,source,true,high,low,close,round(p),round(p.multiply(BigDecimal.valueOf(2)).subtract(low)),
                round(p.multiply(BigDecimal.valueOf(2)).subtract(high)),round(p.add(range)),round(p.subtract(range)),
                "STANDARD; previous complete RTH session; informational only");
    }
    private static boolean isRth(LocalTime time) { return !time.isBefore(LocalTime.of(9,30)) && time.isBefore(LocalTime.of(16,0)); }
    private static BigDecimal round(BigDecimal v) { return v.setScale(4,RoundingMode.HALF_UP); }

    /** Distances in stock-price dollars, not option premiums. Levels may act as either lower/upper boundaries. */
    public static Map<String,Object> context(Levels levels, List<Candle> history, Instant asOf) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (!levels.available()) { out.put("note",levels.note());return out; }
        var quality = BarQuality.assess(history,1,asOf,1,new BarQuality.Policy(1,2,20,1));
        if (!quality.valid()) { out.put("note","Stock context unavailable: " + quality.reason());return out; }
        var latest = quality.completed().get(quality.completed().size()-1);
        var local = latest.timestamp().atZone(ET);
        if (!local.toLocalDate().equals(levels.sessionDate()) || !isRth(local.toLocalTime())) {
            out.put("note","No current reference-session RTH stock candle");return out;
        }
        BigDecimal price = latest.close();
        out.put("stockPrice",price);out.put("stockCandleAt",com.trading.api.TimeFormat.toEt(latest.timestamp()));
        var prices = new LinkedHashMap<String,BigDecimal>();
        prices.put("S2",levels.s2());prices.put("S1",levels.s1());prices.put("P",levels.pivot());prices.put("R1",levels.r1());prices.put("R2",levels.r2());
        Map.Entry<String,BigDecimal> lower = null, upper = null;
        for (var e:prices.entrySet()) {
            if (e.getValue().compareTo(price)<=0 && (lower==null || e.getValue().compareTo(lower.getValue())>0)) lower=e;
            if (e.getValue().compareTo(price)>=0 && (upper==null || e.getValue().compareTo(upper.getValue())<0)) upper=e;
        }
        if (lower!=null) {
            out.put("nearestLowerLevel",lower.getKey());out.put("lowerLevelPrice",lower.getValue());
            out.put("distanceToLower",round(price.subtract(lower.getValue())));
        }
        if (upper!=null) {
            out.put("nearestUpperLevel",upper.getKey());out.put("upperLevelPrice",upper.getValue());
            out.put("distanceToUpper",round(upper.getValue().subtract(price)));
        }
        return out;
    }
}
