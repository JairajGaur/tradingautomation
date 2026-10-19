package com.trading.service;

import com.trading.config.AppProperties;
import com.trading.model.Candle;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Timestamp-based, causal validation shared by option signals and underlying HA. */
public final class BarQuality {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private BarQuality() {}

    public record Policy(int maxAgeBars, int graceSeconds, int gapWindow, int maxMissingBars) {
        public static Policy defaults() { return new Policy(1, 0, 20, 1); }
        public static Policy from(AppProperties.Alerts.BarQualityCfg cfg) {
            return cfg == null ? defaults() : new Policy(Math.max(1, cfg.maxAgeBars()),
                    Math.max(0, cfg.freshnessGraceSeconds()), Math.max(2, cfg.gapWindow()),
                    Math.max(0, cfg.maxMissingBars()));
        }
    }

    public record Result(List<Candle> completed, String reason) {
        public boolean valid() { return reason == null; }
    }

    /** Sort and collapse identical duplicates; never guess which conflicting print is correct. */
    public static List<Candle> canonical(List<Candle> source) {
        var byTime = new TreeMap<Instant, Candle>();
        if (source == null) return List.of();
        for (Candle c : source) {
            if (c == null) throw new IllegalArgumentException("INVALID_OHLCV");
            Candle prior = byTime.putIfAbsent(c.timestamp(), c);
            if (prior != null && !same(prior, c)) throw new IllegalArgumentException("CONFLICTING_DUPLICATE");
        }
        return List.copyOf(byTime.values());
    }

    private static boolean same(Candle a, Candle b) {
        return a.ticker().equals(b.ticker()) && a.volume() == b.volume()
                && java.util.Objects.equals(a.open(), b.open()) && java.util.Objects.equals(a.high(), b.high())
                && java.util.Objects.equals(a.low(), b.low()) && java.util.Objects.equals(a.close(), b.close());
    }

    public static Result assess(List<Candle> source, int minutes, Instant asOf, int minimum, Policy policy) {
        if (minutes <= 0) return new Result(List.of(), "INVALID_TIMEFRAME");
        List<Candle> completed = new ArrayList<>();
        try {
            // Filter first: a future/forming candle cannot change a historical decision.
            if (source != null) for (Candle c : source) {
                if (c == null) return new Result(List.of(), "INVALID_OHLCV");
                if (!c.timestamp().plusSeconds(minutes * 60L).isAfter(asOf)) completed.add(c);
            }
            completed = canonical(completed);
        } catch (IllegalArgumentException e) {
            return new Result(List.of(), e.getMessage());
        }
        for (Candle c : completed) {
            var time = c.timestamp().atZone(ET);
            if (time.getSecond() != 0 || time.getNano() != 0
                    || (minutes < 60 && time.getMinute() % minutes != 0)) {
                return new Result(completed, "MISALIGNED_BARS");
            }
            if (c.open() == null || c.high() == null || c.low() == null || c.open().signum() <= 0
                    || c.low().signum() <= 0 || c.volume() < 0
                    || c.high().compareTo(c.open().max(c.close())) < 0
                    || c.low().compareTo(c.open().min(c.close())) > 0) {
                return new Result(completed, "INVALID_OHLCV");
            }
        }
        if (completed.size() < Math.max(1, minimum)) return new Result(completed, "INSUFFICIENT_WARMUP");
        Candle last = completed.get(completed.size() - 1);
        if (!last.timestamp().atZone(ET).toLocalDate().equals(asOf.atZone(ET).toLocalDate())
                || !asOf.isBefore(last.timestamp().plusSeconds(
                        minutes * 60L * (1L + policy.maxAgeBars()) + policy.graceSeconds()))) {
            return new Result(completed, "STALE_BARS");
        }
        int missing = 0;
        for (int i = Math.max(1, completed.size() - policy.gapWindow() + 1); i < completed.size(); i++) {
            Candle previous = completed.get(i - 1), current = completed.get(i);
            var a = previous.timestamp().atZone(ET);
            var b = current.timestamp().atZone(ET);
            // Closed overnight and session transitions are not missing active-session prints.
            if (!a.toLocalDate().equals(b.toLocalDate()) || session(a.toLocalTime()) != session(b.toLocalTime())) continue;
            long seconds = java.time.Duration.between(previous.timestamp(), current.timestamp()).getSeconds();
            if (seconds % (minutes * 60L) != 0) return new Result(completed, "MISALIGNED_BARS");
            missing += (int) Math.max(0, seconds / (minutes * 60L) - 1);
            if (missing > policy.maxMissingBars()) return new Result(completed, "EXCESSIVE_GAPS");
        }
        return new Result(completed, null);
    }

    /** Incomplete higher-timeframe buckets are never promoted to valid candles. */
    public static List<Candle> completeAggregates(List<Candle> source, int sourceMinutes, int target, Instant asOf) {
        List<Candle> result = new ArrayList<>();
        int cursor = 0;
        for (Candle aggregate : BarResampler.resample(source, target)) {
            Instant end = aggregate.timestamp().plusSeconds(target * 60L);
            if (end.isAfter(asOf)) continue;
            while (cursor < source.size() && source.get(cursor).timestamp().isBefore(aggregate.timestamp())) cursor++;
            int from = cursor;
            while (cursor < source.size() && source.get(cursor).timestamp().isBefore(end)) cursor++;
            List<Candle> bucket = source.subList(from, cursor);
            if (bucket.size() != target / sourceMinutes) continue;
            boolean complete = true;
            for (int i = 0; i < bucket.size(); i++) {
                if (!bucket.get(i).timestamp().equals(aggregate.timestamp().plusSeconds(i * sourceMinutes * 60L))) {
                    complete = false;
                    break;
                }
            }
            if (complete) result.add(aggregate);
        }
        return result;
    }


    private static int session(LocalTime t) {
        return t.isBefore(LocalTime.of(9, 30)) ? 0 : t.isBefore(LocalTime.of(16, 0)) ? 1 : 2;
    }
}
