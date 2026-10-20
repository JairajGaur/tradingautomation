package com.trading.option;

import com.trading.indicator.EmaCalculator;
import java.time.Instant;
import java.util.Comparator;

/** One delivery per continuous direction of the lowest configured stock EMA interval. */
public final class OptionEmaLatch {
    private Instant lastBar;
    private int direction;
    private long generation;
    private boolean consumed;
    private boolean available;

    public synchronized void update(OptionStockTrendFilter.Series series, Instant asOf) {
        available = false;
        if (series == null || !series.ema() || series.frames().isEmpty()) return;
        var frame = series.frames().stream().min(Comparator.comparingInt(OptionStockTrendFilter.Frame::targetMinutes)).orElseThrow();
        var candles = frame.completedAt(asOf);
        if (candles.isEmpty()) return; // Unavailable data must never rearm a delivered alert.
        int minimum = Math.max(series.fast(), series.slow());
        var closes = candles.stream().map(com.trading.model.Candle::close).toList();
        for (int i = minimum - 1; i < candles.size(); i++) {
            Instant bar = candles.get(i).timestamp();
            if (lastBar != null && !bar.isAfter(lastBar)) continue;
            // Only valid historical prefixes may establish a break/recovery between scans.
            Instant closed = bar.plusSeconds(frame.targetMinutes() * 60L);
            if (frame.completedAt(closed).isEmpty()) continue;
            var prefix = closes.subList(0, i + 1);
            observe(bar, EmaCalculator.calculate(prefix, series.fast()).compareTo(EmaCalculator.calculate(prefix, series.slow())));
        }
        available = lastBar != null && lastBar.equals(candles.get(candles.size() - 1).timestamp());
    }

    synchronized void observe(Instant bar, int comparison) {
        if (lastBar != null && !bar.isAfter(lastBar)) return;
        int next = Integer.signum(comparison);
        if (lastBar == null || direction != next) { generation++; consumed = false; }
        direction = next;
        lastBar = bar;
        available = true;
    }

    public synchronized boolean allows(String side) {
        return available && !consumed && direction == ("CALL".equals(side) ? 1 : -1);
    }
    public synchronized long generation() { return generation; }
    public synchronized void consume(String side, long expectedGeneration) {
        if (generation == expectedGeneration && allows(side)) consumed = true;
    }
}
