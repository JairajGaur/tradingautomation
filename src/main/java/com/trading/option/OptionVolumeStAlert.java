package com.trading.option;

import com.trading.alert.Alert;
import com.trading.alert.AlertHit;
import com.trading.alert.AlertMessageFormat;
import com.trading.alert.OrbSupport;
import com.trading.config.AppProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.CandleRangeAtrFilter;
import com.trading.indicator.SupertrendCalculator;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.option.OptionContract.Type;
import com.trading.option.OptionContractService.LoadedContract;
import com.trading.option.OptionContractService.UnderlyingContracts;
import com.trading.service.MarketDataService;
import com.trading.service.TimeframeSupport;
import com.trading.service.BarQuality;
import java.time.Instant;
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
 * <p>Supertrend-driven, with optional underlying HA side confirmation. This alert DRIVES option
 * loading (there is no scheduler). Each run, for every configured underlying it generates the
 * near-the-money contracts (CALL and PUT) off the current stock price, loads their bars, KEEPS
 * only the OCCs whose Supertrend direction is UP, and for each of those evaluates three legs on
 * the option's own {@code timeframe} (default M1) bars:</p>
 * <ol>
 *   <li>Supertrend rising for {@code stRisingCandles} completed bars;</li>
 *   <li>option close ≥ its {@code emaPeriod}-EMA on each of the last {@code emaAboveCandles} bars
 *       (colour-agnostic);</li>
 *   <li>good volume (optional) — session floor + rising, two-part.</li>
 *   <li>3-minute Supertrend (optional) — when enabled, requires that the 3-minute supertrend is also UP.
 *       This adds an additional filter to ensure the option contract meets a more stringent trend condition.</li>
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
    private final OptionHaDirectionFilter haFilter;
    private final com.trading.service.StockPivotService pivots;
    private final OptionSignalGrouping.Cooldown liveCooldown = new OptionSignalGrouping.Cooldown();

    public OptionVolumeStAlert(AppProperties props, OptionUniverseLoader underlyings,
                               OptionContractService contracts, OptionBarDataManager optionBars,
                               SupertrendService supertrend, OptionHaDirectionFilter haFilter) {
        this(props,underlyings,contracts,optionBars,supertrend,haFilter,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OptionVolumeStAlert(AppProperties props, OptionUniverseLoader underlyings,
                               OptionContractService contracts, OptionBarDataManager optionBars,
                               SupertrendService supertrend, OptionHaDirectionFilter haFilter,
                               com.trading.service.StockPivotService pivots) {
        this.props=props;this.underlyings=underlyings;this.contracts=contracts;
        this.optionBars=optionBars;this.supertrend=supertrend;this.haFilter=haFilter;this.pivots=pivots;
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

        // HA chooses the eligible side when enabled. Option ST/EMA/volume gates still apply.
        Set<Type> bothSides = EnumSet.of(Type.CALL, Type.PUT);
        for (String underlying : underlyings.getUnderlyings()) {
            try {
                Instant asOf = Instant.now();
                List<AlertHit> contractHits = new ArrayList<>();
                Set<Type> allowedSides = bothSides;
                if (c.haDirectionEnabled()) {
                    var direction = haFilter.current(underlying, asOf);
                    if (direction.allowedSide() == null) continue;
                    allowedSides = EnumSet.of(direction.allowedSide());
                }
                UnderlyingContracts uc = contracts.loadUnderlying(underlying, allowedSides);
                if (uc == null || uc.contracts().isEmpty()) continue;

                for (LoadedContract lc : uc.contracts()) {
                    if (!lc.loaded()) continue;
                    AlertHit hit = evaluateContract(lc.contract(), tf, day, c, asOf);
                    if (hit != null) contractHits.add(hit);
                }
                var groups = groupHits(underlying, contractHits, asOf);
                if (pivots != null && !groups.isEmpty()) {
                    var context = pivots.current(underlying,asOf);
                    var levels = context.pivotPoints();
                    String detail = levels.available() ? "\nStock pivots (" + levels.sourceSession() + "): P " + levels.pivot()
                            + " | S1 " + levels.s1() + " S2 " + levels.s2() + " | R1 " + levels.r1() + " R2 " + levels.r2()
                            : "\nStock pivots unavailable: " + levels.note();
                    for (var group : groups) hits.add(new AlertHit(group.alertId(),group.ticker(),group.state(),
                            group.message()+detail,group.direction(),group.price(),group.timeframe()));
                } else hits.addAll(groups);
            } catch (Exception e) {
                log.warn("[OptionVolumeStAlert] {} failed (ignored): {}", underlying, e.getMessage());
            }
        }
        return hits;
    }

    /** Group by stock and side; numeric strike order is display order, never a ranking. */
    List<AlertHit> groupHits(String underlying, List<AlertHit> contracts, Instant evaluatedAt) {
        Instant tick = evaluatedAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        java.util.Map<Type, List<AlertHit>> sides = new java.util.EnumMap<>(Type.class);
        for (AlertHit hit : contracts) sides.computeIfAbsent(OptionContract.parse(hit.ticker()).type(),
                ignored -> new ArrayList<>()).add(hit);
        List<AlertHit> groups = new ArrayList<>();
        for (var side : sides.entrySet()) {
            String key = OptionSignalGrouping.key(underlying, side.getKey().name());
            if (!liveCooldown.allows(key, tick, cfg().cooldownMinutes())) continue;
            var ordered = side.getValue().stream().sorted(java.util.Comparator
                    .comparing((AlertHit hit) -> OptionContract.parse(hit.ticker()).strike())
                    .thenComparing(AlertHit::ticker)).toList();
            StringBuilder message = new StringBuilder(messagePrefix()).append(underlying).append(' ')
                    .append(side.getKey()).append(" — ").append(com.trading.api.TimeFormat.toEt(tick))
                    .append("\nQualifying contracts (unranked):");
            for (var hit : ordered) message.append("\n")
                    .append(OptionContract.parse(hit.ticker()).strike().toPlainString()).append(" @ ")
                    .append(hit.price().toPlainString()).append(" — ").append(hit.ticker());
            groups.add(new AlertHit(id(), underlying, "OPTGROUP|" + key + "|" + tick.getEpochSecond(),
                    message.toString(), side.getKey() == Type.CALL ? AlertMessageFormat.Dir.UP : AlertMessageFormat.Dir.DOWN,
                    null, optionTimeframe()));
        }
        return groups;
    }

    @Override public String dedupeKey(AlertHit hit) {
        String[] parts = hit.state().split("\\|");
        return parts.length == 4 && parts[0].equals("OPTGROUP")
                ? OptionSignalGrouping.key(parts[1], parts[2]) : hit.ticker();
    }

    @Override public void onDeliveryAttempted(AlertHit hit) {
        String[] parts = hit.state().split("\\|");
        if (parts.length == 4 && parts[0].equals("OPTGROUP")) {
            liveCooldown.record(OptionSignalGrouping.key(parts[1], parts[2]), Instant.ofEpochSecond(Long.parseLong(parts[3])));
        }
    }

    /** Evaluates one contract; returns a hit when ST-up + ST-rising + above-EMA + (volume) all pass. */
    private AlertHit evaluateContract(OptionContract contract, String tf, String day,
                                      AppProperties.Alerts.OptionVolStCfg c) {
        return evaluateContract(contract, tf, day, c, Instant.now());
    }

    private AlertHit evaluateContract(OptionContract contract, String tf, String day,
                                      AppProperties.Alerts.OptionVolStCfg c, Instant asOf) {
        String occ = contract.occSymbol();
        BarQuality.Result quality = liveBars(occ, tf, asOf);
        if (!quality.valid()) {
            log.warn("[OptionVolumeStAlert] Skipping {}: {}", occ, quality.reason());
            return null;
        }
        List<Candle> bars = quality.completed();

        var candleSize = candleSize(bars);
        if (!candleSize.passes()) {
            log.debug("[OptionVolumeStAlert] {} rejected: {}", occ, candleSize.reason());
            return null;
        }

        // Gate 0: only ST-UP contracts are considered at all.
        if (!stDirectionUp(bars)) return null;

        // New gate: check that the supertrend on configured timeframe is also UP (if enabled)
        if (c.threeMinStEnabled() && !threeMinStDirectionUp(bars, c.threeMinStTimeframe(), asOf)) return null;

        boolean stRising = stRisingStreak(bars) >= Math.max(1, c.stRisingCandles());
        boolean aboveEma = closeAboveEma(bars, c.emaPeriod(), c.emaAboveCandles());
        boolean emaStack = !c.emaStackEnabled()
                || emaStackUpAt(bars, asOf);
        boolean volOk = !c.volumeEnabled()
                || goodVolume(bars, c.minSessionVolume(), c.volumeLookback(), day);

        if (!(stRising && aboveEma && emaStack && volOk)) return null;
        if (c.haDirectionEnabled() && !haFilter.current(contract.underlying(), asOf).allows(contract.type())) return null;

        Candle last = bars.get(bars.size() - 1);
        String state = "OPTST_" + day + "_" + occ;   // internal candidate state; grouped delivery owns cooldown
        return new AlertHit(id(), occ, state,
                optionMessage(contract, last.close()),
                AlertMessageFormat.Dir.UP, last.close(), tf);
    }

    private CandleRangeAtrFilter.Result candleSize(List<Candle> completed) {
        if (!cfg().largeCandleFilterEnabled() || cfg().maxCandleAtr() == null || cfg().maxCandleAtr().signum() <= 0) {
            return CandleRangeAtrFilter.evaluate(null, null, null);
        }
        var points = supertrend.calculate(completed);
        var latest = points.isEmpty() ? null : points.get(points.size() - 1);
        return CandleRangeAtrFilter.evaluate(latest == null ? null : latest.candle(),
                latest == null ? null : latest.atr(), cfg().maxCandleAtr());
    }

    private BarQuality.Policy policy() { return BarQuality.Policy.from(cfg().barQuality()); }

    private int minimumBars() {
        var c = cfg();
        int needed = Math.max(supertrend.length() + Math.max(1, c.stRisingCandles()),
                Math.max(1, c.emaPeriod()) + Math.max(1, c.emaAboveCandles()) - 1);
        if (c.emaStackEnabled()) {
            int source = TimeframeSupport.minutesOf(optionTimeframe());
            int target = TimeframeSupport.minutesOf(emaStackTimeframe());
            int ratio = source > 0 && target >= source && target % source == 0 ? target / source : 1;
            needed = Math.max(needed, Math.max(1, Math.max(c.emaStackFast(), c.emaStackSlow())) * ratio + ratio - 1);
        }
        if (c.volumeEnabled()) needed = Math.max(needed, c.volumeLookback());
        return Math.max(needed, c.barQuality() == null ? 0 : c.barQuality().minWarmupBars());
    }

    private BarQuality.Result liveBars(String occ, String tf, Instant asOf) {
        int count = Math.max(props.engine().warmupBars(), minimumBars() + 1);
        var quality = assessOption(optionBars.getBars(occ, tf, count), tf, asOf);
        if (!quality.valid()) {
            log.info("[OptionVolumeStAlert] Recovering {} {} after {}", occ, tf, quality.reason());
            // Ask for the broker's full option history on recovery, within its 1200-bar cap.
            quality = assessOption(optionBars.refreshBars(occ, tf, 1200), tf, asOf);
        }
        return quality;
    }

    private BarQuality.Result assessOption(List<Candle> source, String tf, Instant asOf) {
        var result = BarQuality.assess(source, TimeframeSupport.minutesOf(tf), asOf, minimumBars(), policy());
        if (!result.valid()) return result;
        if (cfg().emaStackEnabled()) {
            var emaQuality = emaStackBars(result.completed(), tf, asOf);
            if (!emaQuality.valid()) return new BarQuality.Result(result.completed(), "EMA_STACK_" + emaQuality.reason());
        }
        if (!cfg().threeMinStEnabled()) return result;
        int target = TimeframeSupport.minutesOf(cfg().threeMinStTimeframe());
        int minutes = TimeframeSupport.minutesOf(tf);
        if (target < minutes || target % minutes != 0) return new BarQuality.Result(result.completed(), "INVALID_M3_TIMEFRAME");
        var aggregated = target == minutes ? result.completed()
                : BarQuality.completeAggregates(result.completed(), minutes, target, asOf);
        var higher = BarQuality.assess(aggregated, target, asOf, 3,
                new BarQuality.Policy(1, policy().graceSeconds(), policy().gapWindow(), 0));
        return higher.valid() ? result : new BarQuality.Result(result.completed(), "M3_" + higher.reason());
    }

    public String emaStackTimeframe() {
        String configured = cfg().emaStackTimeframe();
        return configured == null || configured.isBlank() ? optionTimeframe() : configured.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Shared completed-bucket selection for live, inspection and historical replay. */
    private BarQuality.Result emaStackBars(List<Candle> completed, String sourceTimeframe, Instant asOf) {
        int source = TimeframeSupport.minutesOf(sourceTimeframe);
        int target = TimeframeSupport.minutesOf(emaStackTimeframe());
        if (source < 1 || target < source || target % source != 0) {
            return new BarQuality.Result(List.of(), "INVALID_TIMEFRAME");
        }
        var bars = source == target ? completed : BarQuality.completeAggregates(completed, source, target, asOf);
        var p = policy();
        return BarQuality.assess(bars, target, asOf,
                Math.max(1, Math.max(cfg().emaStackFast(), cfg().emaStackSlow())),
                source == target ? p : new BarQuality.Policy(p.maxAgeBars(), p.graceSeconds(), p.gapWindow(), 0));
    }

    private boolean emaStackUpAt(List<Candle> completed, Instant asOf) {
        var quality = emaStackBars(completed, optionTimeframe(), asOf);
        return quality.valid() && emaStackUp(quality.completed(), cfg().emaStackFast(), cfg().emaStackSlow());
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
        List<Candle> completed = bars; // callers supply timestamp-validated completed bars
        if (completed.size() < p + need - 1) return false;               // need EMA warm-up + n bars

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
     * True when the option's fast EMA is above its slow EMA on the latest COMPLETED bar
     * ({@code EMA(fast) > EMA(slow)}) — an uptrend confirmation on the option itself.
     */
    private boolean emaStackUp(List<Candle> bars, int fast, int slow) {
        int f = Math.max(1, fast), s = Math.max(1, slow);
        if (bars == null || bars.size() < 2) return false;
        List<Candle> completed = bars; // callers supply timestamp-validated completed bars
        if (completed.size() < s) return false;                      // need warm-up for the slow EMA
        List<BigDecimal> closes = new ArrayList<>(completed.size());
        for (Candle b : completed) closes.add(b.close());
        BigDecimal emaFast = EmaCalculator.calculate(closes, f);
        BigDecimal emaSlow = EmaCalculator.calculate(closes, s);
        return emaFast.compareTo(emaSlow) > 0;
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
            if (bars.size() < lookback) return false;
            List<Candle> completed = bars;
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

    /**
     * Whether the 3-minute supertrend direction is in the UP direction.
     * This method calculates the supertrend for the given timeframe and checks if it's currently UP.
     */
    private boolean threeMinStDirectionUp(List<Candle> bars, String timeframe, Instant asOf) {
        // We need to calculate a supertrend with the specified timeframe
        int length = 3;
        BigDecimal factor = BigDecimal.valueOf(3);

        try {
            // Normalize the timeframe to make sure it's valid
            timeframe = MarketDataService.normaliseTimespan(timeframe);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid timeframe '{}' for 3-minute supertrend calculation: {}", timeframe, e.getMessage());
            return false;
        }

        int sourceMinutes = TimeframeSupport.minutesOf(optionTimeframe());
        int targetMinutes = TimeframeSupport.minutesOf(timeframe);
        // A higher timeframe must contain whole source candles. Never relabel
        // M1 prices as M3, or invent finer bars from a coarser source.
        if (sourceMinutes <= 0 || targetMinutes < sourceMinutes
                || targetMinutes % sourceMinutes != 0) return false;
        List<Candle> recentBars = targetMinutes == sourceMinutes
                ? bars : BarQuality.completeAggregates(bars, sourceMinutes, targetMinutes, asOf);
        var quality = BarQuality.assess(recentBars, targetMinutes, asOf, length,
                new BarQuality.Policy(1, policy().graceSeconds(), policy().gapWindow(), 0));
        if (!quality.valid()) return false;
        recentBars = quality.completed();

        try {
            // Calculate the supertrend with a 3-bar length and factor of 3.0
            List<Point> points = SupertrendCalculator.calculate(recentBars, length, factor);

            // All supplied points are timestamp-confirmed completed candles.
            int lastCompletedIndex = points.size() - 1;
            if (lastCompletedIndex >= 0 && points.get(lastCompletedIndex) != null) {
                Point lastPoint = points.get(lastCompletedIndex);
                return lastPoint.supertrend() != null && lastPoint.direction() == Direction.UP;
            }
        } catch (Exception e) {
            // If there's any error in calculation, return false to be conservative
            log.warn("Error calculating {} supertrend for option contract: {}", timeframe, e.getMessage());
        }

        return false;
    }

    private List<Point> completedStPoints(List<Candle> bars) {
        List<Point> pts = supertrend.calculate(bars);
        List<Point> withLine = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
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
            boolean aboveEma, boolean emaStackUp, boolean volumeOk, long sessionVolume,
            double recentAvgVol, double olderAvgVol, boolean qualifies, boolean threeMinStUp, boolean haDirectionOk, String haColor, boolean candleSizeOk, BigDecimal candleRangeAtr, String note) {}

    /** The configured option timeframe (normalised). */
    public String optionTimeframe() {
        return MarketDataService.normaliseTimespan(cfg().timeframe());
    }

    /** Evaluates a single OCC contract's legs right now (read-only; no delivery). */
    public LegEval evaluateLegs(OptionContract contract) {
        return evaluateLegs(contract, Instant.now());
    }

    private LegEval evaluateLegs(OptionContract contract, Instant asOf) {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        String occ = contract.occSymbol();
        String tf = optionTimeframe();
        String day = asOf.atZone(ET).toLocalDate().toString();
        BarQuality.Result quality = liveBars(occ, tf, asOf);
        List<Candle> bars = quality.completed();

        String type = contract.type().name();
        String strike = contract.strike().toPlainString();
        String moneyness = contract.moneyness() == null ? null : contract.moneyness().name();

        if (!quality.valid()) {
            return new LegEval(occ, type, strike, moneyness, bars.size(), false, false, 0,
                    false, false, false, 0, 0, 0, false, false, false, "UNAVAILABLE", false, null, quality.reason());
        }

        boolean stDirUp = stDirectionUp(bars);
        int streak = stRisingStreak(bars);
        boolean stRising = streak >= Math.max(1, c.stRisingCandles());
        boolean aboveEma = closeAboveEma(bars, c.emaPeriod(), c.emaAboveCandles());
        boolean emaStack = !c.emaStackEnabled() || emaStackUpAt(bars, asOf);

        VolumeStat vs = volumeStat(bars, day, c.volumeLookback());
        boolean volOk;
        if (!c.volumeEnabled()) {
            volOk = true;
        } else {
            boolean floorOk = c.minSessionVolume() <= 0 || vs.sessionVolume >= c.minSessionVolume();
            boolean risingOk = c.volumeLookback() < 2 || vs.recentAvg > vs.olderAvg;
            volOk = floorOk && risingOk;
        }

        // Add check for 3-minute supertrend (if enabled)
        boolean threeMinStUp = !c.threeMinStEnabled() || threeMinStDirectionUp(bars, c.threeMinStTimeframe(), asOf);

        var haDirection = c.haDirectionEnabled()
                ? haFilter.current(contract.underlying(), asOf) : null;
        boolean haOk = haDirection == null || haDirection.allows(contract.type());
        var candleSize = candleSize(bars);
        boolean qualifies = stDirUp && stRising && aboveEma && emaStack && volOk && threeMinStUp && haOk && candleSize.passes();
        return new LegEval(occ, type, strike, moneyness, bars.size(), stDirUp, stRising, streak,
                aboveEma, emaStack, volOk, vs.sessionVolume, vs.recentAvg, vs.olderAvg, qualifies, threeMinStUp, haOk, haDirection == null ? "DISABLED" : haDirection.color(),
                candleSize.passes(), candleSize.rangeAtr(), candleSize.reason());
    }

    // =======================================================================
    // Intraday replay (bar-by-bar over today's RTH session, no lookahead).
    // =======================================================================

    /** One fired signal in a replay: the completed-bar time it fired on + the leg metrics then. */
    public record ReplaySignal(
            String occSymbol, String type, String strike, String moneyness,
            String firedAtEt, String optionClose, int stRisingStreak,
            boolean aboveEma, boolean emaStackUp, boolean volumeOk, String evaluatedAtEt,
            Long sessionVolume, Long signalBarVolume, Long recentFiveBarVolume) {
        public ReplaySignal(String occSymbol, String type, String strike, String moneyness,
                            String firedAtEt, String optionClose, int stRisingStreak,
                            boolean aboveEma, boolean emaStackUp, boolean volumeOk, String evaluatedAtEt) {
            this(occSymbol, type, strike, moneyness, firedAtEt, optionClose, stRisingStreak,
                    aboveEma, emaStackUp, volumeOk, evaluatedAtEt, null, null, null);
        }
    }

    /** The replay outcome for one contract: candidate signals + bars examined. */
    public record ContractReplay(
            String occSymbol, String type, String strike, String moneyness,
            int rthBars, List<ReplaySignal> signals, java.util.Map<String, Integer> dataQualityRejections,
            int largeCandleRejections) {
        public ContractReplay(String occSymbol, String type, String strike, String moneyness,
                              int rthBars, List<ReplaySignal> signals, java.util.Map<String, Integer> dataQualityRejections) {
            this(occSymbol, type, strike, moneyness, rthBars, signals, dataQualityRejections, 0);
        }
        public ContractReplay(String occSymbol, String type, String strike, String moneyness,
                              int rthBars, List<ReplaySignal> signals) {
            this(occSymbol, type, strike, moneyness, rthBars, signals, java.util.Map.of());
        }
    }

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
        return replayContract(contract, allBars, date, true);
    }

    /** Unthrottled candidates for the stock/side replay coordinator. */
    public ContractReplay replayCandidates(OptionContract contract, List<Candle> allBars, LocalDate date) {
        return replayContract(contract, allBars, date, false);
    }

    private ContractReplay replayContract(OptionContract contract, List<Candle> allBars, LocalDate date,
                                          boolean legacyContractCooldown) {
        AppProperties.Alerts.OptionVolStCfg c = cfg();
        String occ = contract.occSymbol();
        String type = contract.type().name();
        String strike = contract.strike().toPlainString();
        String moneyness = contract.moneyness() == null ? null : contract.moneyness().name();

        List<ReplaySignal> signals = new ArrayList<>();
        java.util.Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
        if (allBars == null || allBars.size() < 2) {
            return new ContractReplay(occ, type, strike, moneyness, 0, signals);
        }

        if (allBars.stream().anyMatch(java.util.Objects::isNull)) {
            return new ContractReplay(occ, type, strike, moneyness, 0, signals, java.util.Map.of("INVALID_OHLCV", 1));
        }
        allBars = new ArrayList<>(allBars);
        allBars.sort(java.util.Comparator.comparing(Candle::timestamp));
        OptionHaDirectionFilter.Series haSeries = c.haDirectionEnabled()
                ? haFilter.load(contract.underlying()) : null;
        long cooldownSec = 60L * Math.max(0, c.cooldownMinutes());
        java.time.Instant cooldownUntil = null;
        int rthBars = 0;
        int largeCandleRejections = 0;

        // Evaluate at each historical bar's start; timestamp validation excludes forming/future data.
        for (int i = 1; i < allBars.size(); i++) {
            Candle bar = allBars.get(i);
            if (bar.timestamp().equals(allBars.get(i - 1).timestamp())) continue;
            java.time.ZonedDateTime zt = bar.timestamp().atZone(RZONE);
            if (!zt.toLocalDate().equals(date)) continue;
            java.time.LocalTime t = zt.toLocalTime();
            if (t.isBefore(RTH_OPEN) || !t.isBefore(RTH_CLOSE)) continue;
            Candle previous = allBars.get(i - 1);
            java.time.ZonedDateTime previousEt = previous.timestamp().atZone(RZONE);
            if (!previousEt.toLocalDate().equals(date)
                    || previousEt.toLocalTime().isBefore(RTH_OPEN)) continue;
            rthBars++;

            if (legacyContractCooldown && cooldownUntil != null && bar.timestamp().isBefore(cooldownUntil)) continue;

            if (haSeries != null) {
                var direction = haSeries.at(bar.timestamp());
                if (!direction.allows(contract.type())) {
                    if ("UNAVAILABLE".equals(direction.color())) rejected.merge("HA_UNAVAILABLE", 1, Integer::sum);
                    continue;
                }
            }

            var quality = assessOption(allBars.subList(0, i + 1), optionTimeframe(), bar.timestamp());
            if (!quality.valid()) {
                rejected.merge(quality.reason(), 1, Integer::sum);
                continue;
            }
            List<Candle> upTo = quality.completed();
            if (!candleSize(upTo).passes()) {
                largeCandleRejections++;
                continue;
            }
            if (!stDirectionUp(upTo)) continue;

            // Add check for supertrend on configured timeframe in replay too (if enabled)
            if (c.threeMinStEnabled() && !threeMinStDirectionUp(upTo, c.threeMinStTimeframe(), bar.timestamp())) continue;

            boolean stRising = stRisingStreak(upTo) >= Math.max(1, c.stRisingCandles());
            boolean aboveEma = closeAboveEma(upTo, c.emaPeriod(), c.emaAboveCandles());
            boolean emaStack = !c.emaStackEnabled() || emaStackUpAt(upTo, bar.timestamp());
            boolean volOk = !c.volumeEnabled()
                    || goodVolume(upTo, c.minSessionVolume(), c.volumeLookback(), date.toString());
            if (!(stRising && aboveEma && emaStack && volOk)) continue;

            // Fire — record the signal at the last COMPLETED bar (i-1), apply cooldown.
            Candle completed = upTo.get(upTo.size() - 1);
            var sessionBars = upTo.stream().filter(candle -> {
                var et = candle.timestamp().atZone(RZONE);
                return et.toLocalDate().equals(date) && !et.toLocalTime().isBefore(RTH_OPEN)
                        && et.toLocalTime().isBefore(RTH_CLOSE);
            }).toList();
            long observedSessionVolume = sessionBars.stream().mapToLong(Candle::volume).sum();
            long lastFiveVolume = sessionBars.subList(Math.max(0, sessionBars.size() - 5), sessionBars.size())
                    .stream().mapToLong(Candle::volume).sum();
            signals.add(new ReplaySignal(occ, type, strike, moneyness,
                    com.trading.api.TimeFormat.toEt(completed.timestamp()),
                    completed.close().toPlainString(), stRisingStreak(upTo), aboveEma, emaStack, volOk,
                    com.trading.api.TimeFormat.toEt(bar.timestamp()), observedSessionVolume, completed.volume(), lastFiveVolume));
            cooldownUntil = bar.timestamp().plusSeconds(cooldownSec);
        }
        return new ContractReplay(occ, type, strike, moneyness, rthBars, signals, java.util.Map.copyOf(rejected), largeCandleRejections);
    }

    private record VolumeStat(long sessionVolume, double recentAvg, double olderAvg) {}

    private VolumeStat volumeStat(List<Candle> bars, String day, int lookback) {
        LocalDate today = LocalDate.parse(day);
        long session = 0L;
        for (Candle cnd : bars) {
            if (cnd.timestamp().atZone(ET).toLocalDate().equals(today)) session += cnd.volume();
        }
        double recentAvg = 0, olderAvg = 0;
        if (lookback >= 2 && bars.size() >= lookback) {
            List<Candle> completed = bars;
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
