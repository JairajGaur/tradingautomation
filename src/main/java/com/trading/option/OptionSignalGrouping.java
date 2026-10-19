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

/** One stock/side alert per evaluation, with a cooldown shared across all its strikes. No ranking. */
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
                        List<OptionVolumeStAlert.ReplaySignal> candidates) {}
    public record Replay(List<Group> groups, List<OptionVolumeStAlert.ContractReplay> contracts,
                         int rawContractSignals, int simultaneousGroups, int cooldownSuppressedGroups) {}

    /** Collect ALL qualifying candidates first; never apply per-contract cooldown before grouping. */
    public static Replay replay(String underlying, List<OptionVolumeStAlert.ContractReplay> raw, int minutes) {
        var byTime = new java.util.TreeMap<Instant, Map<String, List<OptionVolumeStAlert.ReplaySignal>>>();
        int count = 0;
        for (var contract : raw) for (var signal : contract.signals()) {
            byTime.computeIfAbsent(instant(signal.evaluatedAtEt()), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(signal.type(), ignored -> new ArrayList<>()).add(signal);
            count++;
        }
        var cooldown = new Cooldown();
        List<Group> groups = new ArrayList<>();
        Map<String, List<OptionVolumeStAlert.ReplaySignal>> accepted = new LinkedHashMap<>();
        int simultaneous = 0, suppressed = 0;
        for (var tick : byTime.entrySet()) for (var side : tick.getValue().entrySet()) {
            simultaneous++;
            String key = key(underlying, side.getKey());
            if (!cooldown.allows(key, tick.getKey(), minutes)) { suppressed++; continue; }
            cooldown.record(key, tick.getKey());
            // Numeric strike order is presentation only; no "best" candidate is selected.
            var candidates = side.getValue().stream().sorted(Comparator
                    .comparing((OptionVolumeStAlert.ReplaySignal s) -> new java.math.BigDecimal(s.strike()))
                    .thenComparing(OptionVolumeStAlert.ReplaySignal::occSymbol)).toList();
            groups.add(new Group(underlying, side.getKey(), TimeFormat.toEt(tick.getKey()), candidates));
            for (var candidate : candidates) accepted.computeIfAbsent(candidate.occSymbol(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<OptionVolumeStAlert.ContractReplay> filtered = new ArrayList<>();
        for (var c : raw) if (accepted.containsKey(c.occSymbol())) {
            filtered.add(new OptionVolumeStAlert.ContractReplay(c.occSymbol(), c.type(), c.strike(), c.moneyness(),
                    c.rthBars(), accepted.get(c.occSymbol()), c.dataQualityRejections(), c.largeCandleRejections()));
        }
        return new Replay(groups, filtered, count, simultaneous, suppressed);
    }
}
