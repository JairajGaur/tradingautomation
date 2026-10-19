package com.trading.service;

import com.trading.indicator.DailyPivotCalculator;
import com.trading.model.Candle;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared read-only live pivot context. Reuses one RTH history fetch per stock/wall minute. */
@Service
public class StockPivotService {
    private final MarketDataService data;
    private record Snapshot(long minute,List<Candle> bars) {}
    private final Map<String,Snapshot> cache = new ConcurrentHashMap<>();
    public StockPivotService(MarketDataService data) { this.data=data; }
    public record Result(DailyPivotCalculator.Levels pivotPoints,Map<String,Object> stockContext) {}
    public Result current(String raw,Instant asOf) {
        String symbol=raw.trim().toUpperCase(Locale.ROOT);
        LocalDate session=asOf.atZone(ZoneId.of("America/New_York")).toLocalDate();
        try {
            long minute=Instant.now().getEpochSecond()/60;
            var snapshot=cache.compute(symbol,(key,old)->old!=null && old.minute()==minute ? old
                    : new Snapshot(minute,data.fetchHistoricalBars(symbol,1650,"M1","RTH")));
            var levels=DailyPivotCalculator.calculate(symbol,snapshot.bars(),session,asOf);
            return new Result(levels,DailyPivotCalculator.context(levels,snapshot.bars(),asOf));
        } catch (RuntimeException ex) {
            return new Result(DailyPivotCalculator.unavailable(symbol,session,null,"Stock history unavailable"),Map.of("note","Stock history unavailable"));
        }
    }
}
