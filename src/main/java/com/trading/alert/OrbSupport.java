package com.trading.alert;

import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Opening-Range-Breakout helpers used by the up/down ORB alerts.
 *
 * <p>The opening range is the high/low of today's <b>09:30 ET candle on the
 * {@code orb-range-minutes} timeframe</b> (15 → M15 = 09:30–09:45, 30 → M30 = 09:30–10:00).
 * It's fetched from historical bars — so it's correct even if the app starts late — and
 * cached once per ticker per day. Breakouts are then checked on the {@code scan-minutes}
 * timeframe close, with an EMA-slope confirmation.</p>
 */
public final class OrbSupport {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final LocalTime OPEN = LocalTime.of(9, 30);

    private OrbSupport() {}

    /** Cached opening range for a ticker on a given day. */
    public record Range(BigDecimal high, BigDecimal low) {}

    // Cache key = "ticker|yyyy-MM-dd" → the day's opening range.
    private static final Map<String, Range> RANGE_CACHE = new ConcurrentHashMap<>();

    /** Today's date key in ET (for per-day cache / dedupe / re-arm). */
    public static String todayKey() {
        return LocalDate.now(ET).toString();
    }

    // -----------------------------------------------------------------------
    // Breakout-leg tracking (so a stale, extended break never re-alerts).
    //
    // A genuine ORB signal is the FIRST close beyond the range. After that, price
    // trending far away is NOT a new breakout — only a close back INSIDE the range,
    // followed by a fresh break, is. We model this with a per-ticker+direction "leg"
    // sequence that advances ONLY on a break that follows a re-entry. The alert's
    // state string embeds the leg, so the engine's dedupe (post only when state
    // changes) naturally suppresses the stale extended re-fires while still posting a
    // true re-break. The alert must emit the (same-state) hit on EVERY above-range bar
    // so it stays "matching" and the engine does not clear its dedupe between legs.
    // -----------------------------------------------------------------------

    /** Per key ("ticker|day|dir") breakout-leg state: current leg #, and whether price has
     *  re-entered the range since that leg fired (arming the next leg). */
    static final class LegState {
        int leg = 0;              // 0 = no breakout yet today
        boolean reentered = true; // true = inside/not-yet-broken → the next break opens a new leg
    }

    /** Outcome of advancing the leg for one bar. {@code leg==0} → inside the range (no breakout);
     *  {@code newLeg} → this bar OPENED a fresh leg (gates should be checked); otherwise it's a
     *  continuation of an existing leg (same state → engine dedupes it). */
    record LegResult(int leg, boolean newLeg) {}

    /**
     * Advances the breakout-leg state for one bar. A leg opens only on a break that follows a
     * re-entry (or the first break of the day); a continued break keeps the same leg; a close
     * back inside the range re-arms the next leg.
     *
     * @param state       per-key mutable state (live: shared map in the alert; backtest: local)
     * @param brokeOut    the latest completed bar closed beyond the range in this direction
     * @param insideRange the latest completed bar closed back INSIDE the range
     */
    static LegResult advanceLeg(LegState state, boolean brokeOut, boolean insideRange) {
        if (insideRange) {
            state.reentered = true;          // armed: next break starts a new leg
            return new LegResult(0, false);  // inside → not a breakout
        }
        if (brokeOut) {
            if (state.reentered) {           // fresh break after a re-entry (or first of day)
                state.leg++;
                state.reentered = false;
                return new LegResult(state.leg, true);   // NEW leg — caller applies gates
            }
            return new LegResult(state.leg, false);      // continuation of the current leg
        }
        return new LegResult(0, false);      // neither → no hit
    }

    /**
     * Marks a leg that {@link #advanceLeg} opened but whose confluence gates then FAILED as
     * consumed, so it is NOT re-attempted on later bars of the same breakout.
     *
     * <p>Critically it must NOT re-arm the leg ({@code reentered} stays {@code false}): price is
     * still beyond the range, it has not closed back inside, so there is no fresh leg to start.
     * {@code advanceLeg} already set {@code reentered = false} when it opened this leg, so the
     * next still-broken-out bar is a continuation (same leg, no re-evaluation) — the gate is only
     * ever evaluated ONCE per leg, on its opening break. The next leg opens only after a genuine
     * {@code insideRange} close re-arms the state.</p>
     *
     * <p>Without this, a gate-failing break re-armed the leg every bar, so the gate was
     * re-evaluated on every subsequent above-range bar and fired again as soon as it flickered to
     * pass — which made enabling the gate <em>increase</em> alerts instead of narrowing them.</p>
     */
    static void rollbackLeg(LegState state) {
        // Intentionally a no-op on the arm: the opened-then-rejected leg stays "current" and
        // NOT re-entered, so later bars of the same unbroken breakout are continuations.
    }

    /**
     * Resolves an ORB direction's Telegram channel: the direction-specific id if set, else the
     * ORB-parent default, else null (no channel — the engine skips delivery). There is no
     * global/common fallback.
     */
    static String resolveChannel(String directionChatId, String orbDefaultChatId) {
        if (directionChatId != null && !directionChatId.isBlank()) return directionChatId.trim();
        if (orbDefaultChatId != null && !orbDefaultChatId.isBlank()) return orbDefaultChatId.trim();
        return null;
    }

    /**
     * Maps an interval in minutes to the matching {@code M<n>} timeframe token, validated the
     * same way the Swift scanner validates its string timeframe. Native broker minutes
     * (1/5/15/30/60/120/240) map to their native token; non-native minute multiples (e.g.
     * {@code 2 → M2}, {@code 3 → M3}) are SYNTHETIC and transparently resampled from M1 by the
     * market-data layer (see {@code TimeframeSupport} / {@code BarResampler}) — so ORB supports
     * M2/M3 just like the swift scan. Throws if {@code minutes < 1} or not a valid minute form.
     */
    public static String timeframeForMinutes(int minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException("Unsupported ORB timeframe minutes=" + minutes + " (must be >= 1)");
        }
        // Build the M<n> token and run it through the shared normaliser, which accepts native
        // AND synthetic minute timeframes (and rejects anything unsupported) — single source of
        // truth, identical to how the swift scan resolves its timeframe.
        return com.trading.service.MarketDataService.normaliseTimespan("M" + minutes);
    }

    /**
     * Returns today's opening range for {@code ticker} from the range-timeframe bars —
     * the 09:30 ET candle's high/low — computed once and cached. Works on a late start
     * because it reads the (completed) 09:30 candle from history.
     *
     * @param ticker       symbol (cache key)
     * @param rangeTfBars  the ticker's series on the orb-range-minutes timeframe
     */
    public static Range openingRange(String ticker, List<Candle> rangeTfBars) {
        String key = ticker + "|" + todayKey();
        Range cached = RANGE_CACHE.get(key);
        if (cached != null) return cached;

        if (rangeTfBars == null || rangeTfBars.isEmpty()) return null;
        LocalDate today = LocalDate.now(ET);

        // Only treat the 09:30 candle as final once the current time is past its close
        // (09:30 + rangeMinutes), so we never cache an in-progress opening bar.
        int rangeMinutes = minutesOf(rangeTfBars);
        boolean rangeComplete = !ZonedDateTime.now(ET).toLocalTime().isBefore(OPEN.plusMinutes(rangeMinutes));
        if (!rangeComplete) return null;

        for (Candle c : rangeTfBars) {
            ZonedDateTime zt = c.timestamp().atZone(ET);
            if (zt.toLocalDate().equals(today) && zt.toLocalTime().equals(OPEN)) {
                Range r = new Range(c.high(), c.low());
                RANGE_CACHE.put(key, r);
                return r;
            }
        }
        return null;   // 09:30 candle not present yet
    }

    /** Infers the bar length (minutes) of a series from the gap between its last two bars. */
    private static int minutesOf(List<Candle> bars) {
        if (bars.size() < 2) return 1;
        var a = bars.get(bars.size() - 2).timestamp();
        var b = bars.get(bars.size() - 1).timestamp();
        long m = java.time.Duration.between(a, b).toMinutes();
        return m > 0 ? (int) m : 1;
    }

    /** Latest COMPLETED candle in a series (drops the last, possibly in-progress, bar). */
    public static Candle latestCompleted(List<Candle> bars) {
        if (bars == null || bars.size() < 2) return null;
        return bars.get(bars.size() - 2);
    }

    /**
     * EMA slope over {@code lookback} completed candles: returns {@code EMA(now) - EMA(lookback ago)}
     * on {@code bars}, or null if not enough data. Positive = rising, negative = falling.
     */
    static BigDecimal emaSlope(List<Candle> bars, int period, int lookback) {
        if (bars == null || bars.size() < period + lookback + 2) return null;
        // Completed bars only.
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        int n = completed.size();
        if (n < period + lookback) return null;

        List<BigDecimal> closesNow = closes(completed);
        List<BigDecimal> closesPrev = closesNow.subList(0, closesNow.size() - lookback);
        BigDecimal emaNow = EmaCalculator.calculate(closesNow, period);
        BigDecimal emaPrev = EmaCalculator.calculate(closesPrev, period);
        return emaNow.subtract(emaPrev);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }

    static boolean closesAbove(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) > 0;
    }

    static boolean closesBelow(Candle bar, BigDecimal level) {
        return bar != null && level != null && bar.close().compareTo(level) < 0;
    }

    /** True if {@code bar} is a GREEN candle (close &gt; open) — the color for an UP break. */
    static boolean isGreen(Candle bar) {
        return bar != null && bar.close().compareTo(bar.open()) > 0;
    }

    /** True if {@code bar} is a RED candle (close &lt; open) — the color for a DOWN break. */
    static boolean isRed(Candle bar) {
        return bar != null && bar.close().compareTo(bar.open()) < 0;
    }

    /**
     * True once the opening range is COMPLETE for {@code bar} — i.e. the bar closes at or after
     * 09:30 + {@code rangeMinutes} ET (the opening-range candle's close). A breakout must occur
     * on or after this, never on a bar inside the still-forming opening range.
     */
    static boolean isAfterRangeClose(Candle bar, int rangeMinutes) {
        if (bar == null) return false;
        LocalTime t = bar.timestamp().atZone(ET).toLocalTime();
        return !t.isBefore(OPEN.plusMinutes(Math.max(1, rangeMinutes)));
    }
}
