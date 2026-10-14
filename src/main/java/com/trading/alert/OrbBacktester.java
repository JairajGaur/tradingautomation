package com.trading.alert;

import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the live Opening-Range-Breakout logic (both UP and DOWN) over a historical candle
 * series for a given ET date, so the backtest reproduces exactly what the live
 * {@link OrbUpAlert} / {@link OrbDownAlert} would have fired that day.
 *
 * <p>Lives in {@code com.trading.alert} so it can reuse the package-private {@link OrbSupport}
 * helpers ({@code closesAbove/closesBelow}, {@code emaSlope}, {@code latestCompleted}) and the
 * same {@link SupertrendService#slopeConfirms} gate the live alerts use — the breakout + both
 * gates run through the identical code, so the backtest can't drift from live behaviour.</p>
 *
 * <p>Only two live pieces are re-implemented, because they are wall-clock bound and would be
 * wrong for a past date: (1) the opening range is located by scanning for the {@code targetDate}
 * 09:30 candle (not {@code now()}/the live cache), and (2) the engine's dedupe is modelled
 * locally with a per-direction "armed" flag — fire on a break, suppress while still broken out,
 * re-arm when a bar closes back inside the range (matching the live "re-break re-fires" rule).</p>
 */
public final class OrbBacktester {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private OrbBacktester() {}

    /** Public opening-range value (the controller lives outside this package). */
    public record Range(BigDecimal high, BigDecimal low) {}

    /** A fired ORB signal in the replay. */
    public record Signal(Candle bar, Direction direction, BigDecimal breakLevel) {}

    /** One per-bar trace row (debug): the full gate evaluation for a scan bar on the target date. */
    public record Trace(Candle bar, Direction attempted, boolean broke, Boolean emaSlopeOk,
                        Boolean stSlopeOk, boolean fired, String reason) {}

    /** Gate parameters, read from config by the controller and passed in. */
    public record Params(
            int rangeMinutes, boolean requireColor,
            int emaPeriod, int emaSlopeLookback, boolean emaSlopeEnabled,
            boolean stSlopeEnabled, int stSlopeLookback, BigDecimal stSlopeMinAtr, int stSlopeBars,
            boolean stSlopeAcceptFlip, int stSlopeFlipBars,
            boolean upEnabled, boolean downEnabled) {}

    /**
     * The opening range (high/low of the {@code targetDate} 09:30 candle) from the range-tf
     * series, or null if that candle isn't present. Date-parameterised twin of
     * {@link OrbSupport#openingRange} with no {@code now()}/cache.
     */
    public static Range openingRange(List<Candle> rangeTfBars, LocalDate targetDate) {
        if (rangeTfBars == null || rangeTfBars.isEmpty()) return null;
        for (Candle c : rangeTfBars) {
            ZonedDateTime zt = c.timestamp().atZone(ET);
            if (zt.toLocalDate().equals(targetDate) && zt.toLocalTime().equals(OPEN)) {
                return new Range(c.high(), c.low());
            }
        }
        return null;
    }

    /** Public pass-through to {@link OrbSupport#timeframeForMinutes} for callers outside the package. */
    public static String timeframeForMinutes(int minutes) {
        return OrbSupport.timeframeForMinutes(minutes);
    }

    /**
     * Replays ORB on {@code scanBars} for {@code targetDate}, returning the signals that would
     * have fired. {@code rangeBars} supplies the opening range; {@code stBars} (nullable) is the
     * Supertrend-slope gate's independent-timeframe series.
     *
     * @param scanBars    the scan/breakout timeframe series (oldest-first, incl. warm-up)
     * @param rangeBars   the range timeframe series (to locate the 09:30 opening candle)
     * @param stBars      the ST-slope gate timeframe series, or null when the gate is off
     * @param targetDate  the ET date to replay
     * @param supertrend  the shared SupertrendService (for slopeConfirms, configured length/factor)
     * @param p           gate parameters
     */
    public static List<Signal> run(List<Candle> scanBars, List<Candle> rangeBars, List<Candle> stBars,
                                   LocalDate targetDate, SupertrendService supertrend, Params p) {
        List<Signal> out = new ArrayList<>();
        replay(scanBars, rangeBars, stBars, targetDate, supertrend, p, out, null);
        return out;
    }

    /** Same replay, additionally collecting a per-bar trace into {@code traceOut}. */
    public static List<Trace> trace(List<Candle> scanBars, List<Candle> rangeBars, List<Candle> stBars,
                                    LocalDate targetDate, SupertrendService supertrend, Params p) {
        List<Trace> traceOut = new ArrayList<>();
        replay(scanBars, rangeBars, stBars, targetDate, supertrend, p, new ArrayList<>(), traceOut);
        return traceOut;
    }

    // -------------------------------------------------------------------------

    private static void replay(List<Candle> scanBars, List<Candle> rangeBars, List<Candle> stBars,
                               LocalDate targetDate, SupertrendService supertrend, Params p,
                               List<Signal> signals, List<Trace> traceOut) {
        Range range = openingRange(rangeBars, targetDate);
        if (range == null || scanBars == null || scanBars.isEmpty()) return;

        // Per-direction breakout-leg state, IDENTICAL to the live alerts (OrbSupport.advanceLeg):
        // a leg opens only on a fresh break that follows a re-entry; a stale extended break keeps
        // the same leg (no re-fire); a close back inside the range re-arms the next leg. So a
        // signal here corresponds to a leg-OPENING break — the same thing the live engine posts.
        OrbSupport.LegState upLeg = new OrbSupport.LegState();
        OrbSupport.LegState downLeg = new OrbSupport.LegState();

        for (int i = 1; i < scanBars.size(); i++) {
            Candle bar = scanBars.get(i);                // treat bar i as the just-completed bar
            ZonedDateTime zt = bar.timestamp().atZone(ET);
            if (!zt.toLocalDate().equals(targetDate)) continue;   // earlier bars are warm-up
            if (zt.toLocalTime().isBefore(OPEN)) continue;

            List<Candle> seriesToHere = scanBars.subList(0, i + 1);

            boolean afterRangeClose = OrbSupport.isAfterRangeClose(bar, p.rangeMinutes());

            // Evaluate UP.
            if (p.upEnabled()) {
                boolean brokeOut = afterRangeClose && OrbSupport.closesAbove(bar, range.high());
                OrbSupport.LegResult lr = OrbSupport.advanceLeg(upLeg, brokeOut, !brokeOut);
                if (lr.leg() > 0 && lr.newLeg()) {
                    Boolean emaOk = null, stOk = null;
                    boolean pass = true;
                    String reason = "break above " + plain(range.high());
                    if (p.requireColor() && !OrbSupport.isGreen(bar)) {
                        pass = false; reason = "break candle not green";
                    }
                    if (pass && p.emaSlopeEnabled()) {
                        BigDecimal slope = OrbSupport.emaSlope(appended(seriesToHere, bar),
                                p.emaPeriod(), Math.max(1, p.emaSlopeLookback()));
                        emaOk = slope != null && slope.signum() >= 0;
                        if (!emaOk) { pass = false; reason = "EMA slope not rising"; }
                    }
                    if (pass && p.stSlopeEnabled() && stBars != null) {
                        stOk = supertrend.slopeConfirms(stBarsThrough(stBars, bar), Direction.UP,
                                p.stSlopeLookback(), p.stSlopeMinAtr(), p.stSlopeBars(),
                                p.stSlopeAcceptFlip(), p.stSlopeFlipBars());
                        if (!stOk) { pass = false; reason = "ST slope not advancing up"; }
                    }
                    if (pass) {
                        signals.add(new Signal(bar, Direction.UP, range.high()));
                    } else {
                        OrbSupport.rollbackLeg(upLeg);   // gates failed → don't open this leg
                    }
                    if (traceOut != null) traceOut.add(new Trace(bar, Direction.UP, true, emaOk, stOk, pass, reason));
                }
            }

            // Evaluate DOWN: mirror.
            if (p.downEnabled()) {
                boolean brokeOut = afterRangeClose && OrbSupport.closesBelow(bar, range.low());
                OrbSupport.LegResult lr = OrbSupport.advanceLeg(downLeg, brokeOut, !brokeOut);
                if (lr.leg() > 0 && lr.newLeg()) {
                    Boolean emaOk = null, stOk = null;
                    boolean pass = true;
                    String reason = "break below " + plain(range.low());
                    if (p.requireColor() && !OrbSupport.isRed(bar)) {
                        pass = false; reason = "break candle not red";
                    }
                    if (pass && p.emaSlopeEnabled()) {
                        BigDecimal slope = OrbSupport.emaSlope(appended(seriesToHere, bar),
                                p.emaPeriod(), Math.max(1, p.emaSlopeLookback()));
                        emaOk = slope != null && slope.signum() <= 0;
                        if (!emaOk) { pass = false; reason = "EMA slope not falling"; }
                    }
                    if (pass && p.stSlopeEnabled() && stBars != null) {
                        stOk = supertrend.slopeConfirms(stBarsThrough(stBars, bar), Direction.DOWN,
                                p.stSlopeLookback(), p.stSlopeMinAtr(), p.stSlopeBars(),
                                p.stSlopeAcceptFlip(), p.stSlopeFlipBars());
                        if (!stOk) { pass = false; reason = "ST slope not advancing down"; }
                    }
                    if (pass) {
                        signals.add(new Signal(bar, Direction.DOWN, range.low()));
                    } else {
                        OrbSupport.rollbackLeg(downLeg);
                    }
                    if (traceOut != null) traceOut.add(new Trace(bar, Direction.DOWN, true, emaOk, stOk, pass, reason));
                }
            }
        }
    }

    /**
     * The live gates drop the LAST candle as in-progress and evaluate the one before it. In the
     * replay, bar i IS the completed bar we want evaluated, so we append a duplicate as the
     * "in-progress" bar the helper will drop — making bar i the evaluated bar (same trick the
     * Swift backtester uses).
     */
    private static List<Candle> appended(List<Candle> through, Candle last) {
        List<Candle> w = new ArrayList<>(through);
        w.add(last);
        return w;
    }

    /** The ST-slope series up to (and including, duplicated) the bar at/just before {@code bar}'s time. */
    private static List<Candle> stBarsThrough(List<Candle> stBars, Candle bar) {
        long t = bar.timestamp().toEpochMilli();
        int cut = stBars.size();
        for (int i = 0; i < stBars.size(); i++) {
            if (stBars.get(i).timestamp().toEpochMilli() > t) { cut = i; break; }
        }
        List<Candle> through = new ArrayList<>(stBars.subList(0, cut));
        if (!through.isEmpty()) through.add(through.get(through.size() - 1));  // in-progress dup
        return through;
    }

    /**
     * HH:mm ET <b>close/end time</b> of a signal bar — i.e. the bar's open-timestamp plus its
     * length in minutes. The engine timestamps a candle by its OPEN minute, but the Webull app
     * labels each intraday candle by its CLOSE minute; reporting the end time here makes the
     * backtest's signal clock line up with the candle the user actually sees on the Webull chart
     * (e.g. an M2 break the engine stamps 09:52 is Webull's 09:54 candle). Label-only: the
     * breakout/color/leg logic is unchanged.
     *
     * @param c          the signal bar (open-stamped)
     * @param barMinutes the scan timeframe length in minutes (M2 → 2, M3 → 3, …)
     */
    public static String timeEt(Candle c, int barMinutes) {
        return c.timestamp().atZone(ET).plusMinutes(Math.max(0, barMinutes)).format(HM);
    }

    /**
     * HH:mm ET OPEN time of a signal bar. Prefer {@link #timeEt(Candle, int)} for user-facing
     * output so the label matches the Webull chart's close-time candle labelling.
     */
    public static String timeEt(Candle c) {
        return c.timestamp().atZone(ET).format(HM);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
