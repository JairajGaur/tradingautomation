package com.trading.option;

import com.trading.alert.Alert;
import com.trading.alert.AlertHit;
import com.trading.alert.AlertMessageFormat;
import com.trading.alert.OrbSupport;
import com.trading.config.AppProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.option.OptionContract.Type;
import com.trading.option.OptionContractService.LoadedContract;
import com.trading.option.OptionContractService.UnderlyingContracts;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * OPTIONS Supertrend alert — see {@link AppProperties.Alerts.OptionVolStCfg} for the spec.
 *
 * <p>Pure Supertrend-driven: no ORB, no stock side gate, no latch. This alert DRIVES option
 * loading (there is no scheduler). Each run, for every configured underlying it generates the
 * near-the-money contracts (CALL and PUT) off the current stock price, loads their bars, KEEPS
 * only the OCCs whose Supertrend direction is UP, and for each of those evaluates three legs on
 * the option's own {@code timeframe} (default M1) bars:</p>
 * <ol>
 *   <li>Supertrend rising for {@code stRisingCandles} completed bars;</li>
 *   <li>option close ≥ its {@code emaPeriod}-EMA on each of the last {@code emaAboveCandles} bars
 *       (colour-agnostic);</li>
 *   <li>good volume (optional) — session floor + rising, two-part.</li>
 * </ol>
 * Fires a single UP signal per qualifying OCC. The {@link com.trading.alert.AlertEngine} discovers
 * it via {@code List<Alert>}, polls it every minute, and gates it to market hours. The stock
 * universe the engine passes to {@link #evaluate} is ignored — this alert iterates the configured
 * option UNDERLYINGS.
 */
@Component
public class OptionVolumeStAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(OptionVolumeStAlert.class);

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final AppProperties props;
    private final OptionUniverseLoader underlyings;
    private final OptionContractService contracts;
    private final OptionBarDataManager optionBars;
    private final SupertrendService supertrend;

    public OptionVolumeStAlert(AppProperties props,
                               OptionUniverseLoader underlyings,
                               OptionContractService contracts,
                               OptionBarDataManager optionBars,
                               SupertrendService supertrend) {
        this.props = props;
        this.underlyings = underlyings;
        this.contracts = contracts;
        this.optionBars = optionBars;
        this.supertrend = supertrend;
    }

    private AppProperties.Alerts.OptionVolStCfg cfg() {
        return props.alerts() == null ? null : props.alerts().optionVolSt();
    }

    @Override public String id() { return "option-vol-st"; }

    @Override public boolean isEnabled() {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        return c != null && c.enabled();
    }

    @Override public String messagePrefix() {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        return c == null || c.messagePrefix() == null ? "" : c.messagePrefix();
    }

    @Override public int scanIntervalMinutes() { return 1; }   // poll every minute

    @Override public String channelId() {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        String id = c == null ? null : c.telegramChatId();
        return (id == null || id.isBlank()) ? null : id.trim();
    }

    /** Option bars are read via OptionBarDataManager inside evaluate — nothing for the engine to prefetch. */
    @Override public List<String> timeframesNeeded() { return List.of(); }

    @Override
    public List<AlertHit> evaluate(List<String> ignoredStockUniverse) {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        if (c == null) return List.of();

        final String tf;
        try {
            tf = MarketDataService.normaliseTimespan(c.timeframe());
        } catch (IllegalArgumentException e) {
            log.warn("[OptionVolumeStAlert] invalid timeframe '{}': {}", c.timeframe(), e.getMessage());
            return List.of();
        }

        String day = OrbSupport.todayKey();
        List<AlertHit> hits = new ArrayList<>();

        // Load BOTH sides near-the-money off the current price for each underlying, then keep
        // only ST-up contracts and evaluate the EMA + volume legs.
        Set<Type> bothSides = EnumSet.of(Type.CALL, Type.PUT);
        for (String underlying : underlyings.getUnderlyings()) {
            try {
                UnderlyingContracts uc = contracts.loadUnderlying(underlying, bothSides);
                if (uc == null || uc.contracts().isEmpty()) continue;

                for (LoadedContract lc : uc.contracts()) {
                    if (!lc.loaded()) continue;
                    AlertHit hit = evaluateContract(lc.contract(), tf, day, c);
                    if (hit != null) hits.add(hit);
                }
            } catch (Exception e) {
                log.warn("[OptionVolumeStAlert] {} failed (ignored): {}", underlying, e.getMessage());
            }
        }
        return hits;
    }

    /** Evaluates one contract; returns a hit when ST-up + ST-rising + above-EMA + (volume) all pass. */
    private AlertHit evaluateContract(OptionContract contract, String tf, String day,
                                      AppProperties.Alerts.OptionVolStCfg c) {
        String occ = contract.occSymbol();
        List<Candle> bars = optionBars.getBars(occ, tf, props.engine().warmupBars());
        if (bars == null || bars.isEmpty()) return null;

        // Gate 0: only ST-UP contracts are considered at all.
        if (!stDirectionUp(bars)) return null;

        boolean stRising = stRisingStreak(bars) >= Math.max(1, c.stRisingCandles());
        boolean aboveEma = closeAboveEma(bars, c.emaPeriod(), c.emaAboveCandles());
        boolean volOk = !c.volumeEnabled()
                || goodVolume(bars, c.minSessionVolume(), c.volumeLookback(), day);

        if (!(stRising && aboveEma && volOk)) return null;

        Candle last = bars.get(bars.size() - 1);
        String state = "OPTST_" + day + "_" + occ;   // unique per day; engine dedupes same-state
        return new AlertHit(id(), occ, state,
                optionMessage(contract, last.close()),
                AlertMessageFormat.Dir.UP, last.close(), tf);
    }

    /**
     * Builds the option alert message: {@code <prefix><UNDERLYING> <TYPE> <STRIKE> <LAST_PRICE>}
     * — e.g. {@code OPTION ST - TSLA PUT 450 1.43}.
     */
    private String optionMessage(OptionContract contract, BigDecimal lastPrice) {
        String prefix = messagePrefix() == null ? "" : messagePrefix();
        return prefix
                + contract.underlying() + " "
                + contract.type().name() + " "
                + contract.strike().toPlainString() + " "
                + lastPrice.toPlainString();
    }

    // -----------------------------------------------------------------------
    // Legs
    // -----------------------------------------------------------------------

    /** True when the option close is AT OR ABOVE its {@code period}-EMA on each of the last
     *  {@code n} completed bars (candle colour irrelevant). */
    private boolean closeAboveEma(List<Candle> bars, int period, int n) {
        int need = Math.max(1, n);
        int p = Math.max(1, period);
        if (bars == null || bars.size() < 2) return false;
        List<Candle> completed = bars.subList(0, bars.size() - 1);   // drop in-progress bar
        if (completed.size() < p + need) return false;               // need EMA warm-up + n bars

        List<BigDecimal> closes = new ArrayList<>(completed.size());
        for (Candle b : completed) closes.add(b.close());

        // For each of the last `need` completed bars, compute EMA(period) up to that bar and
        // require close >= EMA at that bar.
        for (int k = 0; k < need; k++) {
            int end = closes.size() - k;             // exclusive index for this bar's prefix
            List<BigDecimal> prefix = closes.subList(0, end);
            if (prefix.size() < p) return false;
            BigDecimal ema = EmaCalculator.calculate(prefix, p);
            BigDecimal close = closes.get(end - 1);
            if (close.compareTo(ema) < 0) return false;   // below EMA on this bar
        }
        return true;
    }

    /**
     * Two-part volume gate: (a) cumulative session volume ≥ {@code minSessionVolume}, AND
     * (b) rising volume (recent-half avg > older-half avg over {@code lookback}). Either
     * sub-check disabled by a non-positive config.
     */
    private boolean goodVolume(List<Candle> bars, long minSessionVolume, int lookback, String day) {
        if (minSessionVolume > 0) {
            LocalDate today = LocalDate.parse(day);
            long session = 0L;
            for (Candle c : bars) {
                if (c.timestamp().atZone(ET).toLocalDate().equals(today)) session += c.volume();
            }
            if (session < minSessionVolume) return false;
        }
        if (lookback >= 2) {
            if (bars.size() < lookback + 1) return false;
            List<Candle> completed = bars.subList(0, bars.size() - 1);
            if (completed.size() < lookback) return false;
            List<Candle> window = completed.subList(completed.size() - lookback, completed.size());
            int half = lookback / 2;
            long olderSum = 0L, recentSum = 0L;
            for (int i = 0; i < half; i++) olderSum += window.get(i).volume();
            for (int i = lookback - half; i < lookback; i++) recentSum += window.get(i).volume();
            if (recentSum / (double) half <= olderSum / (double) half) return false;
        }
        return true;
    }

    /** How many consecutive latest completed bars the ST line has strictly risen (0 if none). */
    private int stRisingStreak(List<Candle> bars) {
        List<Point> withLine = completedStPoints(bars);
        int streak = 0;
        for (int i = withLine.size() - 1; i >= 1; i--) {
            if (withLine.get(i).supertrend().compareTo(withLine.get(i - 1).supertrend()) > 0) streak++;
            else break;
        }
        return streak;
    }

    /** Whether the latest completed ST point is in the UP direction. */
    private boolean stDirectionUp(List<Candle> bars) {
        List<Point> withLine = completedStPoints(bars);
        return !withLine.isEmpty() && withLine.get(withLine.size() - 1).direction() == Direction.UP;
    }

    private List<Point> completedStPoints(List<Candle> bars) {
        List<Point> pts = supertrend.calculate(bars);
        List<Point> withLine = new ArrayList<>();
        for (int i = 0; i < pts.size() - 1; i++) {
            if (pts.get(i).supertrend() != null) withLine.add(pts.get(i));
        }
        return withLine;
    }

    // =======================================================================
    // Inspection / dry-run API (used by the REST endpoints; read-only, no delivery).
    // =======================================================================

    /** The full leg evaluation of one contract (with the metrics behind each leg). */
    public record LegEval(
            String occSymbol, String type, String strike, String moneyness,
            int barsUsed, boolean stDirectionUp, boolean stRising, int stRisingStreak,
            boolean aboveEma, boolean volumeOk, long sessionVolume, double recentAvgVol, double olderAvgVol,
            boolean qualifies, String note) {}

    /** The configured option timeframe (normalised). */
    public String optionTimeframe() {
        return MarketDataService.normaliseTimespan(cfg().timeframe());
    }

    /** Evaluates a single OCC contract's legs right now (read-only; no delivery). */
    public LegEval evaluateLegs(OptionContract contract) {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        String occ = contract.occSymbol();
        String tf = optionTimeframe();
        String day = OrbSupport.todayKey();
        List<Candle> bars = optionBars.getBars(occ, tf, props.engine().warmupBars());

        String type = contract.type().name();
        String strike = contract.strike().toPlainString();
        String moneyness = contract.moneyness() == null ? null : contract.moneyness().name();

        if (bars == null || bars.isEmpty()) {
            return new LegEval(occ, type, strike, moneyness, 0, false, false, 0,
                    false, false, 0, 0, 0, false, "no option bars");
        }

        boolean stDirUp = stDirectionUp(bars);
        int streak = stRisingStreak(bars);
        boolean stRising = streak >= Math.max(1, c.stRisingCandles());
        boolean aboveEma = closeAboveEma(bars, c.emaPeriod(), c.emaAboveCandles());

        VolumeStat vs = volumeStat(bars, day, c.volumeLookback());
        boolean volOk;
        if (!c.volumeEnabled()) {
            volOk = true;
        } else {
            boolean floorOk = c.minSessionVolume() <= 0 || vs.sessionVolume >= c.minSessionVolume();
            boolean risingOk = c.volumeLookback() < 2 || vs.recentAvg > vs.olderAvg;
            volOk = floorOk && risingOk;
        }

        boolean qualifies = stDirUp && stRising && aboveEma && volOk;
        return new LegEval(occ, type, strike, moneyness, bars.size(), stDirUp, stRising, streak,
                aboveEma, volOk, vs.sessionVolume, vs.recentAvg, vs.olderAvg, qualifies, null);
    }

    // =======================================================================
    // Intraday replay (bar-by-bar over today's RTH session, no lookahead).
    // =======================================================================

    /** One fired signal in a replay: the completed-bar time it fired on + the leg metrics then. */
    public record ReplaySignal(
            String occSymbol, String type, String strike, String moneyness,
            String firedAtEt, String optionClose, int stRisingStreak, boolean aboveEma, boolean volumeOk) {}

    /** The replay outcome for one contract: fired signals (cooldown-deduped) + bars examined. */
    public record ContractReplay(
            String occSymbol, String type, String strike, String moneyness,
            int rthBars, List<ReplaySignal> signals) {}

    private static final java.time.ZoneId RZONE = java.time.ZoneId.of("America/New_York");
    private static final java.time.LocalTime RTH_OPEN  = java.time.LocalTime.of(9, 30);
    private static final java.time.LocalTime RTH_CLOSE = java.time.LocalTime.of(16, 0);

    /**
     * Replays ONE option contract over {@code date}'s RTH session (09:30–16:00 ET): walks the
     * {@code timeframe} bars bar-by-bar and, at each completed bar, evaluates the alert's legs on
     * ONLY the data available up to that bar (no lookahead). Records a fired signal when all
     * enabled legs pass, then suppresses re-fires for {@code cooldownMinutes}. Prior-day bars in
     * the fetched series seed the Supertrend/EMA warm-up.
     *
     * @param allBars the full fetched option series (oldest-first; may span several days)
     * @param date    the ET session date to replay
     */
    public ContractReplay replayContract(OptionContract contract, List<Candle> allBars, LocalDate date) {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        String occ = contract.occSymbol();
        String type = contract.type().name();
        String strike = contract.strike().toPlainString();
        String moneyness = contract.moneyness() == null ? null : contract.moneyness().name();

        List<ReplaySignal> signals = new ArrayList<>();
        if (allBars == null || allBars.size() < 2) {
            return new ContractReplay(occ, type, strike, moneyness, 0, signals);
        }

        long cooldownSec = 60L * Math.max(0, c.cooldownMinutes());
        java.time.Instant cooldownUntil = null;
        int rthBars = 0;

        // Walk each bar; a bar at index i is treated as the "just-formed current bar", so the
        // leg methods (which drop the last element) evaluate completed data through i-1 — the
        // same view the live alert has on the tick right after bar i closes.
        for (int i = 1; i < allBars.size(); i++) {
            Candle bar = allBars.get(i);
            java.time.ZonedDateTime zt = bar.timestamp().atZone(RZONE);
            if (!zt.toLocalDate().equals(date)) continue;
            java.time.LocalTime t = zt.toLocalTime();
            if (t.isBefore(RTH_OPEN) || t.isAfter(RTH_CLOSE)) continue;
            rthBars++;

            if (cooldownUntil != null && !bar.timestamp().isAfter(cooldownUntil)) continue;  // in cooldown

            List<Candle> upTo = allBars.subList(0, i + 1);   // includes bar i as the "forming" bar
            if (!stDirectionUp(upTo)) continue;
            boolean stRising = stRisingStreak(upTo) >= Math.max(1, c.stRisingCandles());
            boolean aboveEma = closeAboveEma(upTo, c.emaPeriod(), c.emaAboveCandles());
            boolean volOk = !c.volumeEnabled()
                    || goodVolume(upTo, c.minSessionVolume(), c.volumeLookback(), date.toString());
            if (!(stRising && aboveEma && volOk)) continue;

            // Fire — record the signal at the last COMPLETED bar (i-1), apply cooldown.
            Candle completed = allBars.get(i - 1);
            signals.add(new ReplaySignal(occ, type, strike, moneyness,
                    com.trading.api.TimeFormat.toEt(completed.timestamp()),
                    completed.close().toPlainString(), stRisingStreak(upTo), aboveEma, volOk));
            cooldownUntil = bar.timestamp().plusSeconds(cooldownSec);
        }
        return new ContractReplay(occ, type, strike, moneyness, rthBars, signals);
    }

    private record VolumeStat(long sessionVolume, double recentAvg, double olderAvg) {}

    private VolumeStat volumeStat(List<Candle> bars, String day, int lookback) {
        LocalDate today = LocalDate.parse(day);
        long session = 0L;
        for (Candle cnd : bars) {
            if (cnd.timestamp().atZone(ET).toLocalDate().equals(today)) session += cnd.volume();
        }
        double recentAvg = 0, olderAvg = 0;
        if (lookback >= 2 && bars.size() >= lookback + 1) {
            List<Candle> completed = bars.subList(0, bars.size() - 1);
            if (completed.size() >= lookback) {
                List<Candle> window = completed.subList(completed.size() - lookback, completed.size());
                int half = lookback / 2;
                long olderSum = 0, recentSum = 0;
                for (int i = 0; i < half; i++) olderSum += window.get(i).volume();
                for (int i = lookback - half; i < lookback; i++) recentSum += window.get(i).volume();
                olderAvg = olderSum / (double) half;
                recentAvg = recentSum / (double) half;
            }
        }
        return new VolumeStat(session, recentAvg, olderAvg);
    }
}
