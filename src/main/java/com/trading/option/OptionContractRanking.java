package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.service.BarQuality;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Nearby qualifying strikes first, then completed signal-candle contract volume. */
public final class OptionContractRanking {
    private OptionContractRanking() {}
    public record Candidate(String occSymbol, BigDecimal strike, Long candleVolume) {}
    public record Selection(String occSymbol, BigDecimal stockPrice, BigDecimal strikeDistance,
                            long signalCandleVolume, BigDecimal closestStrikeDistance,
                            BigDecimal distanceLimit, int qualifyingContracts, int nearbyContracts) {}
    public static boolean enabled(AppProperties.Alerts.ContractRankingCfg cfg) { return cfg!=null && cfg.enabled(); }
    public static Selection select(List<Candidate> candidates, BigDecimal stockPrice, BigDecimal gap,
                                   AppProperties.Alerts.ContractRankingCfg cfg) {
        if (candidates==null || stockPrice==null || stockPrice.signum()<=0 || gap==null || gap.signum()<=0
                || cfg==null || cfg.nearbyStrikeGaps()==null || cfg.nearbyStrikeGaps().signum()<0) return null;
        var validStrikes=candidates.stream().filter(c -> c!=null && c.occSymbol()!=null && !c.occSymbol().isBlank()
                && c.strike()!=null && c.strike().signum()>0).toList();
        BigDecimal closest=validStrikes.stream().map(c->c.strike().subtract(stockPrice).abs()).min(BigDecimal::compareTo).orElse(null);
        if (closest==null) return null;
        BigDecimal limit=closest.add(gap.multiply(cfg.nearbyStrikeGaps()));
        var nearby=validStrikes.stream().filter(c->c.candleVolume()!=null && c.candleVolume()>=0
                && c.strike().subtract(stockPrice).abs().compareTo(limit)<=0).toList();
        var chosen=nearby.stream().sorted(Comparator.comparingLong((Candidate c)->c.candleVolume()).reversed()
                .thenComparing(c->c.strike().subtract(stockPrice).abs()).thenComparing(Candidate::strike)
                .thenComparing(Candidate::occSymbol)).findFirst().orElse(null);
        return chosen==null ? null : new Selection(chosen.occSymbol(),stockPrice,chosen.strike().subtract(stockPrice).abs(),
                chosen.candleVolume(),closest,limit,candidates.size(),nearby.size());
    }
    /** Same completed stock reference in live and historical replay, never the session open or a future close. */
    public static BigDecimal stockPriceAt(List<Candle> history, Instant asOf, AppProperties.Alerts.BarQualityCfg cfg) {
        var quality=BarQuality.assess(history,1,asOf,1,BarQuality.Policy.from(cfg));
        if (!quality.valid()) return null;
        var latest=quality.completed().get(quality.completed().size()-1);
        var et=ZoneId.of("America/New_York");var local=latest.timestamp().atZone(et);
        return local.toLocalDate().equals(asOf.atZone(et).toLocalDate())
                && !local.toLocalTime().isBefore(LocalTime.of(9,30)) && local.toLocalTime().isBefore(LocalTime.of(16,0))
                ? latest.close() : null;
    }
}
