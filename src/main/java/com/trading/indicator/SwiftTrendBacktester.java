package com.trading.indicator;

import com.trading.indicator.SwiftTrendDetector.Result;
import com.trading.indicator.SwiftTrendDetector.Thresholds;
import com.trading.indicator.SwiftTrendScanner.Decision;
import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a historical candle series through the <b>live scanner's own firing logic</b>
 * ({@link SwiftTrendScanner}) exactly as it would run live — one completed bar at a time —
 * and records every moment a swift UP/DOWN signal would have fired on a target ET date.
 *
 * <p><b>No separate detection or cooldown logic lives here.</b> Each bar is evaluated with
 * {@link SwiftTrendDetector} (via the supplied params) and the fire/suppress decision is
 * made by {@link SwiftTrendScanner#decide} — the identical rule the live
 * {@code SwiftTrendAlert} uses. The only difference is the time base: the replay feeds the
 * BAR's timestamp as the "now" for the cooldown (so cooldown is measured in market time),
 * and uses its own local cooldown-state map so it never touches live state.</p>
 *
 * <p>Warm-up bars from <em>before</em> the target date are intentionally included so
 * Supertrend/ATR are properly seeded going into the day; only signals whose bar falls on
 * the target date are reported.</p>
 */
public final class SwiftTrendBacktester {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private SwiftTrendBacktester() {
        // utility class
    }

    /**
     * A single recorded signal during the replay.
     *
     * @param candle the completed bar on which the signal fired
     * @param result the detector result (direction + metrics + reason)
     */
    public record Signal(Candle candle, Result result) {}

    /**
     * A per-bar trace entry for debugging: the bar plus the raw detector result (metrics +
     * reason) and whether it WOULD fire after cooldown. Uses the identical detection code as
     * {@link #run}; this just exposes every bar's evaluation instead of only the firing ones.
     *
     * @param candle the completed bar evaluated
     * @param result the detector result (swift flag, direction, metrics, reason)
     * @param wouldFire true if this bar would actually fire live (swift AND past cooldown)
     */
    public record Trace(Candle candle, Result result, boolean wouldFire) {}

    /**
     * Replays {@code candles} and returns a per-bar trace for {@code targetDate} — every
     * evaluated bar with its detector metrics/reason and whether it would fire after the
     * cooldown. Same detection + cooldown rule as {@link #run}; nothing is filtered out.
     */
    public static List<Trace> trace(SwiftTrendScanner scanner, List<Candle> candles, LocalDate targetDate,
                                    int length, BigDecimal factor,
                                    Thresholds thresholds, int cooldownMinutes) {
        List<Trace> out = new ArrayList<>();
        if (candles == null || candles.size() < length + 3) return out;

        Map<String, SwiftTrendScanner.FireState> fireState = new HashMap<>();

        for (int i = length + 2; i < candles.size(); i++) {
            Candle bar = candles.get(i);
            ZonedDateTime barEt = bar.timestamp().atZone(ET);
            if (!barEt.toLocalDate().equals(targetDate)) continue;

            List<Candle> window;
            if (i + 1 < candles.size()) {
                window = candles.subList(0, i + 2);
            } else {
                window = new ArrayList<>(candles.subList(0, i + 1));
                window.add(bar);
            }

            Result r = SwiftTrendDetector.evaluate(window, length, factor, thresholds);
            long barMs = bar.timestamp().toEpochMilli();
            boolean barRth = scanner.isRegularHours(bar.timestamp());
            Decision d = SwiftTrendScanner.decide(bar.ticker(), r, barMs, cooldownMinutes, barRth, fireState);
            out.add(new Trace(bar, r, d.fire()));
        }
        return out;
    }

    /**
     * Replays {@code candles} and returns the swift signals that fired on {@code targetDate}
     * (ET), using the SAME detect + cooldown rule as the live scanner.
     *
     * @param candles         full series oldest-first (target date plus warm-up)
     * @param targetDate      the ET calendar date to report signals for
     * @param length          Supertrend ATR period (from config)
     * @param factor          Supertrend ATR multiplier (from config)
     * @param thresholds      detection thresholds (from config)
     * @param cooldownMinutes per-direction cooldown in minutes (from config; 0 = none)
     * @return signals in chronological order
     */
    public static List<Signal> run(SwiftTrendScanner scanner, List<Candle> candles, LocalDate targetDate,
                                   int length, BigDecimal factor,
                                   Thresholds thresholds, int cooldownMinutes) {
        List<Signal> signals = new ArrayList<>();
        if (candles == null || candles.size() < length + 3) return signals;

        // Replay-local fire state (never touches the live scanner's map).
        Map<String, SwiftTrendScanner.FireState> fireState = new HashMap<>();

        // Step i evaluates the detector on the series up to bar i (the latest completed bar
        // at this step). Start once enough bars exist to compute Supertrend.
        for (int i = length + 2; i < candles.size(); i++) {
            Candle bar = candles.get(i);                 // latest completed bar at this step
            ZonedDateTime barEt = bar.timestamp().atZone(ET);

            // Only report signals ON the target date (earlier bars are warm-up).
            if (!barEt.toLocalDate().equals(targetDate)) continue;

            // The detector drops the LAST candle as in-progress and evaluates the one before
            // it. To make it evaluate on bar i, feed it the series through i+1 (so bar i+1 is
            // the dropped "in-progress" bar). On the final bar, duplicate it so bar i stays
            // the evaluated one.
            List<Candle> window;
            if (i + 1 < candles.size()) {
                window = candles.subList(0, i + 2);
            } else {
                window = new ArrayList<>(candles.subList(0, i + 1));
                window.add(bar);
            }

            // IDENTICAL to live: detect, then apply the shared fire/cooldown rule — only the
            // time base differs (bar time here vs. wall clock live).
            Result r = SwiftTrendDetector.evaluate(window, length, factor, thresholds);
            long barMs = bar.timestamp().toEpochMilli();
            // RTH gate (same as live): only RTH bars fire, though warmup/compute uses all sessions.
            boolean barRth = scanner.isRegularHours(bar.timestamp());
            // The ticker is read off the bar so the fire-state key matches the live scanner's.
            Decision d = SwiftTrendScanner.decide(bar.ticker(), r, barMs, cooldownMinutes, barRth, fireState);
            if (!d.fire()) continue;

            signals.add(new Signal(bar, d.result()));
        }
        return signals;
    }
}
