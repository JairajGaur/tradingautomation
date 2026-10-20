package com.trading.option;

import com.trading.api.TimeFormat;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stock/side grouping with nearby/volume ranking and production EMA-state rearming. */
public final class OptionSignalGrouping {
    private OptionSignalGrouping() {}
    private static final DateTimeFormatter ET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss z");

    public static String key(String underlying, String side) { return underlying + "|" + side; }
    public static Instant instant(String timeEt) { return ZonedDateTime.parse(timeEt, ET).toInstant(); }

    public static final class Cooldown {
        private final Map<String, Instant> last = new ConcurrentHashMap<>();
        public boolean allows(String key, Instant at, int minutes) {
            Instant prior = last.get(key);
            return prior == null || !at.isBefore(prior.plusSeconds(Math.max(0, minutes) * 60L));
        }
        public void record(String key, Instant at) {
            last.merge(key, at, (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    public record Group(String underlying, String type, String evaluatedAtEt,
                        List<OptionVolumeStAlert.ReplaySignal> candidates, OptionContractRanking.Selection selection) {}
    public record Replay(List<Group> groups, List<OptionVolumeStAlert.ContractReplay> contracts,
                         int rawContractSignals, int simultaneousGroups, int cooldownSuppressedGroups, int rankingSkippedGroups) {}

    /** Legacy time-cooldown overload for existing consumers; production uses replayByEma. */
    public static Replay replay(String underlying, List<OptionVolumeStAlert.ContractReplay> raw, int minutes) {
        return replay(underlying,raw,minutes,null,null,ignored->null);
    }

    public static Replay replay(String underlying, List<OptionVolumeStAlert.ContractReplay> raw, int minutes,
                                com.trading.config.AppProperties.Alerts.ContractRankingCfg cfg,
                                java.math.BigDecimal gap,
                                java.util.function.Function<Instant,java.math.BigDecimal> stockPriceAt) {
        return replayInternal(underlying, raw, minutes, cfg, gap, stockPriceAt, null, false);
    }

    /** Production replay: observe every completed lowest-frame stock EMA bar between candidates. */
    public static Replay replayByEma(String underlying, List<OptionVolumeStAlert.ContractReplay> raw,
                                com.trading.config.AppProperties.Alerts.ContractRankingCfg cfg,
                                java.math.BigDecimal gap,
                                java.util.function.Function<Instant,java.math.BigDecimal> stockPriceAt,
                                OptionStockTrendFilter.Series ema) {
        return replayInternal(underlying, raw, 0, cfg, gap, stockPriceAt, ema, true);
    }

    private static Replay replayInternal(String underlying, List<OptionVolumeStAlert.ContractReplay> raw, int minutes,
                                com.trading.config.AppProperties.Alerts.ContractRankingCfg cfg,
                                java.math.BigDecimal gap,
                                java.util.function.Function<Instant,java.math.BigDecimal> stockPriceAt,
                                OptionStockTrendFilter.Series ema, boolean useEma) {
        var byTime = new java.util.TreeMap<Instant, Map<String, List<OptionVolumeStAlert.ReplaySignal>>>();
        int count = 0;
        for (var contract : raw) for (var signal : contract.signals()) {
            byTime.computeIfAbsent(instant(signal.evaluatedAtEt()), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(signal.type(), ignored -> new ArrayList<>()).add(signal);
            count++;
        }
        var cooldown = new Cooldown();
        var latch = new OptionEmaLatch();
        List<Group> groups = new ArrayList<>();
        Map<String, List<OptionVolumeStAlert.ReplaySignal>> accepted = new LinkedHashMap<>();
        int simultaneous = 0, suppressed = 0, rankingSkipped = 0;
        for (var tick : byTime.entrySet()) for (var side : tick.getValue().entrySet()) {
            simultaneous++;
            String key = key(underlying, side.getKey());
            if (useEma) latch.update(ema, tick.getKey());
            if (useEma ? !latch.allows(side.getKey()) : !cooldown.allows(key, tick.getKey(), minutes)) {
                suppressed++; continue;
            }
            // Rank before consuming cooldown: missing ranking data may recover on the next tick.
            var candidates = side.getValue().stream().sorted(Comparator
                    .comparing((OptionVolumeStAlert.ReplaySignal s) -> new java.math.BigDecimal(s.strike()))
                    .thenComparing(OptionVolumeStAlert.ReplaySignal::occSymbol)).toList();
            OptionContractRanking.Selection selection=null;
            if (OptionContractRanking.enabled(cfg)) {
                selection=OptionContractRanking.select(candidates.stream().map(c->new OptionContractRanking.Candidate(
                        c.occSymbol(),new java.math.BigDecimal(c.strike()),c.signalBarVolume())).toList(),
                        stockPriceAt.apply(tick.getKey()),gap,cfg);
                if (selection==null) { rankingSkipped++;continue; }
                String selected=selection.occSymbol();
                candidates=candidates.stream().filter(c->c.occSymbol().equals(selected)).limit(1).toList();
            }
            if (useEma) latch.consume(side.getKey(), latch.generation());
            else cooldown.record(key, tick.getKey());
            groups.add(new Group(underlying, side.getKey(), TimeFormat.toEt(tick.getKey()), candidates,selection));
            for (var candidate : candidates) accepted.computeIfAbsent(candidate.occSymbol(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<OptionVolumeStAlert.ContractReplay> filtered = new ArrayList<>();
        for (var c : raw) if (accepted.containsKey(c.occSymbol())) {
            filtered.add(new OptionVolumeStAlert.ContractReplay(c.occSymbol(), c.type(), c.strike(), c.moneyness(),
                    c.rthBars(), accepted.get(c.occSymbol()), c.dataQualityRejections(), c.largeCandleRejections()));
        }
        return new Replay(groups, filtered, count, simultaneous, suppressed,rankingSkipped);
    }
}
