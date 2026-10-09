package com.trading.indicator;

import com.trading.config.WebullProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SwiftTrendDetector.Result;
import com.trading.indicator.SwiftTrendDetector.Thresholds;
import com.trading.model.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The <b>single source of truth</b> for the Swift Trend Scanner's firing decision:
 * detection (via {@link SwiftTrendDetector}) PLUS the "fire once per flip" per-symbol+
 * direction cooldown. Both the live scanner ({@code SwiftTrendAlert}) and the backtest
 * ({@code SwiftTrendBacktester}) call this — so the backtest exercises the EXACT same
 * logic that fires live, with no duplicated/parallel implementation that could drift.
 *
 * <p>The firing decision is time-driven but clock-agnostic: callers pass the relevant
 * epoch-millis — the live scanner passes wall-clock {@code System.currentTimeMillis()},
 * the backtest passes the bar's timestamp — so the cooldown is measured consistently in
 * whichever time base is appropriate. Thresholds, timeframe, length/factor and cooldown
 * all come from configuration, so there is exactly one place that defines "what is a
 * swift signal and when do we fire it".</p>
 */
@Component
public class SwiftTrendScanner {

    private final WebullProperties props;
    private final SupertrendService supertrend;
    private final com.trading.service.MarketHoursGuard marketHoursGuard;

    /** Last-fired state per "SYMBOL|DIRECTION" for the live latch + cooldown. */
    private final Map<String, FireState> fireState = new ConcurrentHashMap<>();

    public SwiftTrendScanner(WebullProperties props, SupertrendService supertrend,
                             com.trading.service.MarketHoursGuard marketHoursGuard) {
        this.props = props;
        this.supertrend = supertrend;
        this.marketHoursGuard = marketHoursGuard;
    }

    /**
     * Whether {@code instant} is in the REGULAR (RTH) session per {@code webull.market-hours}.
     * Signals only fire on RTH bars, even though the Supertrend is computed over all
     * configured sessions (PRE/RTH/ATH) so the indicator is seeded continuously.
     */
    public boolean isRegularHours(java.time.Instant instant) {
        return marketHoursGuard.isRegularHours(instant);
    }

    /**
     * Per "SYMBOL|DIRECTION" firing memory.
     *
     * @param flipTime the flip time of the trend leg that last fired (identifies the leg)
     * @param firedAtMs when it fired, epoch-millis (for the secondary cooldown)
     */
    public record FireState(java.time.Instant flipTime, long firedAtMs) {}

    /** The swift-scan config block (may be null if alerts config is absent). */
    public WebullProperties.Alerts.SwiftScanCfg config() {
        return props.alerts() == null ? null : props.alerts().swiftScan();
    }

    /** Supertrend ATR period (configured, shared app-wide). */
    public int length() { return supertrend.length(); }

    /** Supertrend ATR multiplier (configured, shared app-wide). */
    public BigDecimal factor() { return supertrend.factor(); }

    /** Builds the detection thresholds from the swift-scan config. */
    public Thresholds thresholds() {
        return thresholds(config());
    }

    /** Builds the detection thresholds from the given swift-scan config. */
    public Thresholds thresholds(WebullProperties.Alerts.SwiftScanCfg c) {
        return new Thresholds(
                Math.max(1, c.flipWithinBars()),
                c.minTravelAtr(),
                c.minSeparationAtr(),
                Math.max(1, c.strongCandleWindow()),
                Math.max(1, c.minStrongCandles()),
                c.bodyMinAtr(),
                Math.max(1, c.flipWindowBars()),
                Math.max(1, c.maxFlipsInWindow()),
                Math.max(1, c.stSlopeLookback()),
                c.minStSlopeAtr(),
                c.maxCandleAtr());
    }

    /**
     * The pure detection step — "is the latest completed bar a swift move?" — with no
     * cooldown. Delegates entirely to {@link SwiftTrendDetector} using configured params.
     *
     * @param bars OHLC candles oldest-first (may include the in-progress bar)
     * @return the detector result (never null)
     */
    public Result detect(List<Candle> bars) {
        return SwiftTrendDetector.evaluate(bars, length(), factor(), thresholds());
    }

    /**
     * Full firing decision for ONE evaluation: detect, then apply the per-symbol+direction
     * cooldown against this scanner's own fired-state map. This is what the LIVE scanner
     * calls, passing {@code System.currentTimeMillis()} as {@code nowMs}.
     *
     * @param symbol the symbol being evaluated (for the cooldown key)
     * @param bars   OHLC candles oldest-first
     * @param nowMs  current time in epoch-millis (wall clock for live)
     * @return a {@link Decision} — {@code fire=true} only when swift AND out of cooldown
     */
    public Decision evaluate(String symbol, List<Candle> bars, long nowMs) {
        Result r = detect(bars);
        int cooldownMinutes = config().cooldownMinutes();
        // RTH gate: only fire during regular hours. The evaluated bar is the latest COMPLETED
        // bar; classify ITS timestamp (not wall-clock), so this matches the backtest exactly.
        boolean barRth = r.barTime() != null && isRegularHours(r.barTime());
        return decide(symbol, r, nowMs, cooldownMinutes, barRth, this.fireState);
    }

    /**
     * The firing decision given an already-computed {@link Result} and an EXTERNAL
     * fire-state map. This is what the BACKTEST calls — passing the bar's timestamp as
     * {@code atMs} and its own local map — so the replay uses the identical rule as live
     * without touching the live scanner's state. Static so there is one, and only one,
     * implementation of the firing rule.
     *
     * <p><b>Rule — one alert per Supertrend leg:</b> once a signal fires for a
     * {@code symbol|direction}, no further signal fires for the SAME trend leg (identified by
     * the result's {@code flipTime}), no matter how long the move runs or how travel wobbles.
     * A reversal is a NEW flip → new {@code flipTime} → a new leg that fires again (reversals
     * are never suppressed). The configured {@code cooldownMinutes} is a secondary guard: even
     * on a brand-new leg, a signal is suppressed if the previous fire for that
     * {@code symbol|direction} was within the cooldown window.</p>
     *
     * <p><b>RTH gate:</b> even if a bar is swift, it only fires when {@code barIsRegularHours}
     * is true. The Supertrend is computed over all configured sessions (PRE/RTH/ATH) so the
     * indicator is seeded continuously, but alerts are emitted only for regular-hours bars.
     * An out-of-RTH bar is rejected WITHOUT consuming the latch/cooldown, so the first RTH bar
     * that still qualifies in the same leg can fire.</p>
     *
     * @param symbol         cooldown key symbol
     * @param result         the detector result for this bar
     * @param atMs           the time of this evaluation (bar time for backtest)
     * @param cooldownMinutes per-direction cooldown (0 = none)
     * @param barIsRegularHours whether the evaluated bar is in the RTH session
     * @param fireState      caller-owned "SYMBOL|DIRECTION" -> {@link FireState} map
     * @return a {@link Decision}
     */
    public static Decision decide(String symbol, Result result, long atMs,
                                  int cooldownMinutes, boolean barIsRegularHours,
                                  Map<String, FireState> fireState) {
        if (!result.swift() || result.direction() == null) {
            return new Decision(false, result, "not swift");
        }
        // RTH gate — reject (without consuming latch/cooldown) outside regular hours.
        if (!barIsRegularHours) {
            return new Decision(false, result, "outside RTH");
        }
        String key = symbol + "|" + result.direction();
        FireState prev = fireState.get(key);

        // 1. One alert per leg: same leg (same flipTime) already fired → suppress.
        if (prev != null && result.flipTime() != null
                && result.flipTime().equals(prev.flipTime())) {
            return new Decision(false, result, "already fired this leg (flip " + result.flipTime() + ")");
        }

        // 2. Secondary cooldown: suppress a NEW leg if the last fire for this direction was
        //    within the cooldown window (guards against rapid re-flips firing back-to-back).
        long cooldownMs = Math.max(0, cooldownMinutes) * 60_000L;
        if (prev != null && cooldownMs > 0 && (atMs - prev.firedAtMs()) < cooldownMs) {
            return new Decision(false, result, "cooldown");
        }

        fireState.put(key, new FireState(result.flipTime(), atMs));
        return new Decision(true, result, result.reason());
    }

    /**
     * Formats the alert text using the SHARED uniform format
     * ({@link com.trading.alert.AlertMessageFormat}) — same shape as every other alert:
     * {@code <prefix><emoji> <SYMBOL> <DIRECTION> @ <price> [<timeframe>]}.
     */
    public String formatMessage(WebullProperties.Alerts.SwiftScanCfg c, String symbol, Result r) {
        String prefix = c == null || c.messagePrefix() == null ? "" : c.messagePrefix();
        String tf = c == null ? "" : c.timeframe();
        com.trading.alert.AlertMessageFormat.Dir dir = r.direction() == Direction.UP
                ? com.trading.alert.AlertMessageFormat.Dir.UP
                : com.trading.alert.AlertMessageFormat.Dir.DOWN;
        String price = r.close() == null ? "n/a"
                : r.close().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return com.trading.alert.AlertMessageFormat.line(prefix, symbol, dir, price, tf);
    }

    /**
     * Outcome of a firing decision.
     *
     * @param fire   true when a signal should be emitted (swift AND past cooldown)
     * @param result the underlying detector result
     * @param reason why it did or didn't fire ("not swift" / "cooldown" / the swift reason)
     */
    public record Decision(boolean fire, Result result, String reason) {}
}
