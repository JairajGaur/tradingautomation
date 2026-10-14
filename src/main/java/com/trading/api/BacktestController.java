package com.trading.api;

import com.trading.alert.OrbBacktester;
import com.trading.config.AppProperties;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.indicator.SwiftTrendBacktester;
import com.trading.indicator.SwiftTrendBacktester.Signal;
import com.trading.indicator.SwiftTrendDetector.Result;
import com.trading.indicator.SwiftTrendDetector.Thresholds;
import com.trading.indicator.SwiftTrendScanner;
import com.trading.model.Candle;
import com.trading.service.MarketDataException;
import com.trading.service.MarketDataService;
import com.trading.service.TimeframeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — <b>strategy backtests</b>. One controller for replaying a historical day for a
 * single ticker and reporting exactly WHEN each alert strategy would have fired, using the
 * SAME configuration + logic as the live alerts (detection/analysis only — no orders, no
 * notifications).
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Strategy</th></tr>
 *   <tr><td>GET</td><td>/api/swift-scan/backtest/{ticker}</td>
 *       <td>Swift Trend Scanner (Supertrend flip + momentum)</td></tr>
 *   <tr><td>GET</td><td>/api/orb-scan/backtest/{ticker}</td>
 *       <td>Opening-Range Breakout (up/down)</td></tr>
 * </table>
 *
 * <p>Shared params: {@code date} (ET YYYY-MM-DD, default today), {@code sessions}
 * (RTH/PRE/ATH/OVN, default configured), {@code count} (fetch size; auto-sized to reach the
 * date with warm-up), {@code debug} (per-bar trace). Swift adds {@code timeframe}; ORB adds
 * {@code rangeTimeframe}/{@code scanTimeframe}/{@code stSlopeTimeframe} (native or synthetic
 * M2/M3). The broker returns most-recent N bars (no date range), so older dates need a larger
 * {@code count}; the response reports the covered range.</p>
 */
@RestController
@RequestMapping("/api")
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AppProperties props;
    private final MarketDataService marketDataService;
    private final SupertrendService supertrend;
    private final SwiftTrendScanner scanner;

    public BacktestController(AppProperties props,
                             MarketDataService marketDataService,
                             SupertrendService supertrend,
                             SwiftTrendScanner scanner) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.supertrend = supertrend;
        this.scanner = scanner;
    }

    @GetMapping("/swift-scan/backtest/{ticker}")
    public ResponseEntity<Map<String, Object>> backtest(
            @PathVariable String ticker,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false, defaultValue = "false") boolean debug) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) {
            return badRequest("ticker is required");
        }

        // Config + detection params all come from the shared scanner — the SAME source of
        // truth the live alert uses, so the backtest can't diverge from live behaviour.
        AppProperties.Alerts.SwiftScanCfg cfg = scanner.config();
        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "swift-scan config missing", "timestamp", TimeFormat.nowEt()));
        }

        // Target date (ET). Default: today.
        LocalDate targetDate;
        try {
            targetDate = (date != null && !date.isBlank())
                    ? LocalDate.parse(date.trim(), DATE)
                    : LocalDate.now(ET);
        } catch (Exception e) {
            return badRequest("Invalid date '" + date + "' — use YYYY-MM-DD");
        }

        // Timeframe defaults to the scanner's configured timeframe; sessions default to the
        // configured webull.trading.trading-sessions (same sessions the trading loop uses).
        String ts;
        String sess;
        try {
            ts = MarketDataService.normaliseTimespan(
                    (timeframe != null && !timeframe.isBlank()) ? timeframe : cfg.timeframe());
            String requestedSessions = (sessions != null && !sessions.isBlank())
                    ? sessions : props.engine().tradingSessions();
            sess = MarketDataService.normaliseSessions(requestedSessions);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS,
                    "timestamp", TimeFormat.nowEt()));
        }

        int length = scanner.length();
        BigDecimal factor = scanner.factor();
        Thresholds thresholds = scanner.thresholds(cfg);

        // Auto-size the fetch to reach the target date with warm-up. Bars-per-trading-day
        // ≈ RTH minutes / timeframe minutes; multiply by the number of calendar days back
        // plus a warm-up buffer. Capped so we don't request absurd amounts.
        int fetchCount = (count != null && count > 0) ? count : autoCount(ts, targetDate, length);

        List<Candle> candles;
        try {
            candles = marketDataService.fetchHistoricalBars(symbol, fetchCount, ts, sess);
        } catch (MarketDataException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("webullError", e.toDetailMap());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }

        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No market data available for ticker",
                    "ticker", symbol, "timespan", ts, "timestamp", TimeFormat.nowEt()));
        }

        // Range actually covered (ET), for diagnostics / out-of-range detection.
        Candle first = candles.get(0);
        Candle last = candles.get(candles.size() - 1);
        LocalDate firstDate = first.timestamp().atZone(ET).toLocalDate();
        LocalDate lastDate = last.timestamp().atZone(ET).toLocalDate();

        boolean dateInRange = !targetDate.isBefore(firstDate) && !targetDate.isAfter(lastDate);

        // Count how many of the fetched bars fall ON the target date (sanity signal).
        long barsOnDate = candles.stream()
                .filter(c -> c.timestamp().atZone(ET).toLocalDate().equals(targetDate))
                .count();

        // Warmup bars = those BEFORE the target date, used to seed Supertrend/ATR going
        // into the day. Supertrend needs at least `length` bars to produce its first value.
        long warmupBarsUsed = candles.stream()
                .filter(c -> c.timestamp().atZone(ET).toLocalDate().isBefore(targetDate))
                .count();
        boolean warmupSufficient = warmupBarsUsed >= length;

        List<Signal> signals = SwiftTrendBacktester.run(
                scanner, candles, targetDate, length, factor, thresholds, cfg.cooldownMinutes());

        // Simple audit output: a flat, chronological list of the signals the scanner
        // would have caught — one readable line each.
        List<String> audit = new ArrayList<>(signals.size());
        int upCount = 0;
        int downCount = 0;
        for (Signal s : signals) {
            boolean up = s.result().direction() == Direction.UP;
            if (up) upCount++; else downCount++;
            audit.add(auditLine(s));
        }

        // Current (most-recent completed) Supertrend on the configured timeframe — computed
        // from the full fetched series (which runs up to now), so this is the live value.
        Point latestSt = supertrend.latest(candles);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("date", targetDate.toString());
        body.put("timeframe", ts);

        Map<String, Object> currentSt = new LinkedHashMap<>();
        if (latestSt != null) {
            currentSt.put("supertrend", latestSt.supertrend().toPlainString());
            currentSt.put("direction", latestSt.direction() == null ? null : latestSt.direction().name());
            currentSt.put("close", latestSt.candle().close().toPlainString());
            currentSt.put("atr", latestSt.atr() == null ? null : latestSt.atr().toPlainString());
            currentSt.put("barTimeEt", TimeFormat.toEt(latestSt.candle().timestamp()));
        } else {
            currentSt.put("supertrend", null);
            currentSt.put("note", "not enough bars to compute Supertrend (length=" + length + ")");
        }
        body.put("currentSupertrend", currentSt);

        body.put("signalCount", signals.size());
        body.put("swiftUpCount", upCount);
        body.put("swiftDownCount", downCount);
        body.put("signals", audit);

        // Debug: per-bar trace for the target date — every bar's detector metrics + reason,
        // and whether it would fire. Same detection code; nothing filtered. Use to see WHY
        // a bar did/didn't fire so thresholds can be tuned against the chart.
        if (debug) {
            List<SwiftTrendBacktester.Trace> traces = SwiftTrendBacktester.trace(
                    scanner, candles, targetDate, length, factor, thresholds, cfg.cooldownMinutes());
            List<Map<String, Object>> traceRows = new ArrayList<>(traces.size());
            for (SwiftTrendBacktester.Trace tr : traces) {
                traceRows.add(traceRow(tr));
            }
            body.put("debugTrace", traceRows);
        }

        Map<String, Object> warmup = new LinkedHashMap<>();
        warmup.put("warmupBarsUsed", warmupBarsUsed);        // bars BEFORE the target date (seed)
        warmup.put("barsOnTargetDate", barsOnDate);          // bars ON the target date (replayed)
        warmup.put("totalBarsFetched", candles.size());
        warmup.put("supertrendLength", length);              // min bars Supertrend needs to seed
        warmup.put("warmupSufficient", warmupSufficient);    // warmupBarsUsed >= supertrendLength
        body.put("warmup", warmup);

        if (warmupBarsUsed > 0 && !warmupSufficient) {
            body.put("warmupNote", "Only " + warmupBarsUsed + " warmup bar(s) before "
                    + targetDate + " — fewer than supertrend length (" + length + "). Early-day "
                    + "signals may be unreliable until Supertrend is seeded. Increase ?count=.");
        }

        if (!dateInRange) {
            body.put("note", "Requested date is outside the fetched range (" + firstDate
                    + " .. " + lastDate + "). Increase ?count= to reach older dates.");
        } else if (barsOnDate == 0) {
            body.put("note", "No bars on " + targetDate + " (market holiday/weekend, or"
                    + " the ticker had no trades in the requested sessions).");
        }

        log.info("[SwiftScanBacktest] {} {} {} → {} UP, {} DOWN (warmupBars={}, barsOnDate={}, fetched={})",
                symbol, targetDate, ts, upCount, downCount, warmupBarsUsed, barsOnDate, candles.size());

        return ResponseEntity.ok(body);
    }

    /** One debug trace row: bar time + all detector metrics + reason + wouldFire. */
    private Map<String, Object> traceRow(SwiftTrendBacktester.Trace tr) {
        Candle c = tr.candle();
        Result r = tr.result();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timeEt", c.timestamp().atZone(ET).format(DateTimeFormatter.ofPattern("HH:mm")));
        row.put("close", c.close().toPlainString());
        row.put("direction", r.direction() == null ? null : r.direction().name());
        row.put("flipBarsAgo", r.flipBarsAgo());
        row.put("travelAtr", r.travelAtr() == null ? null : r.travelAtr().toPlainString());
        row.put("separationAtr", r.separationAtr() == null ? null : r.separationAtr().toPlainString());
        row.put("strongCandles", r.strongCandles());
        row.put("flipsInWindow", r.flipsInWindow());
        row.put("swift", r.swift());
        row.put("wouldFire", tr.wouldFire());
        row.put("reason", r.reason());
        return row;
    }

    /** One readable audit line: "09:35 ET  🔴 DOWN  @ 229.80  ST 231.40 (DOWN)". */
    private String auditLine(Signal s) {
        Result r = s.result();
        String emoji = r.direction() == Direction.UP ? "🟢" : "🔴";
        String timeEt = s.candle().timestamp().atZone(ET)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        // The signal's direction IS the Supertrend direction (that's what the detector keys
        // on). Round ST to 2 dp and label its direction.
        String st = r.supertrend() == null
                ? "n/a"
                : r.supertrend().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String stDir = r.direction() == null ? "" : " (" + r.direction() + ")";
        String price = r.close() == null ? "n/a"
                : r.close().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return timeEt + " ET  " + emoji + " " + r.direction()
                + "  @ " + price + "  ST " + st + stDir;
    }

    /**
     * Estimates how many most-recent bars to fetch so the series reaches back to
     * {@code targetDate} (plus Supertrend warm-up). Based on ~RTH bars/day for the
     * timeframe and the calendar days from the target date to now, scaled for weekends,
     * then floored at the warm-up size and capped to a sane maximum.
     */
    private int autoCount(String timeframe, LocalDate targetDate, int length) {
        int tfMin = switch (timeframe) {
            case "M1" -> 1; case "M5" -> 5; case "M15" -> 15; case "M30" -> 30;
            case "M60" -> 60; case "M120" -> 120; case "M240" -> 240;
            default -> 0;   // D/W/M/Y — coarse
        };
        long daysBack = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(targetDate, LocalDate.now(ET)));
        // ~390 RTH minutes/day; scale calendar days by 7/5 to account for weekends.
        long tradingDays = (long) Math.ceil((daysBack + 1) * 7.0 / 5.0) + 2;
        long barsPerDay = tfMin > 0 ? Math.max(1, 390 / tfMin) : 1;
        // Allow for extended sessions roughly doubling intraday bars; add warm-up buffer.
        long estimate = tradingDays * barsPerDay * 2 + length * 3L + 50;
        long warmup = props.engine().warmupBars();
        long floored = Math.max(estimate, warmup);
        // Cap: don't request more than ~20k bars (broker/practical limit).
        return (int) Math.min(floored, 20_000L);
    }

    // =========================================================================
    // ORB (Opening-Range-Breakout) backtest — replays the live OrbUp/OrbDown logic
    // via OrbBacktester (shared OrbSupport gates), with configurable range + scan
    // timeframes (native or synthetic M2/M3).
    // =========================================================================

    @GetMapping("/orb-scan/backtest/{ticker}")
    public ResponseEntity<Map<String, Object>> orbBacktest(
            @PathVariable String ticker,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String rangeTimeframe,
            @RequestParam(required = false) String scanTimeframe,
            @RequestParam(required = false) String stSlopeTimeframe,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false, defaultValue = "false") boolean debug) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) return badRequest("ticker is required");

        AppProperties.Alerts.Orb orb = props.alerts() == null ? null : props.alerts().orb();
        if (orb == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "orb config missing", "timestamp", TimeFormat.nowEt()));
        }

        LocalDate targetDate;
        try {
            targetDate = (date != null && !date.isBlank())
                    ? LocalDate.parse(date.trim(), DATE) : LocalDate.now(ET);
        } catch (Exception e) {
            return badRequest("Invalid date '" + date + "' — use YYYY-MM-DD");
        }

        String rangeTf, scanTf, stTf, sess;
        try {
            rangeTf = resolveTf(rangeTimeframe, OrbBacktester.timeframeForMinutes(orb.rangeMinutes()));
            scanTf = resolveTf(scanTimeframe, OrbBacktester.timeframeForMinutes(orb.up().scanMinutes()));
            stTf = orb.stSlopeConfirmEnabled()
                    ? resolveTf(stSlopeTimeframe, MarketDataService.normaliseTimespan(orb.stSlopeTimeframe()))
                    : null;
            String requestedSessions = (sessions != null && !sessions.isBlank())
                    ? sessions : props.engine().tradingSessions();
            sess = MarketDataService.normaliseSessions(requestedSessions);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS,
                    "timestamp", TimeFormat.nowEt()));
        }

        int emaPeriod = orb.emaPeriod();
        int warmupNeeded = emaPeriod + Math.max(orb.emaSlopeLookback(), supertrend.length()) + 10;
        int scanFetch = (count != null && count > 0) ? count : autoCountOrb(scanTf, targetDate, warmupNeeded);

        List<Candle> scanBars, rangeBars, stBars = null;
        try {
            scanBars = marketDataService.fetchHistoricalBars(symbol, scanFetch, scanTf, sess);
            int rangeFetch = autoCountOrb(rangeTf, targetDate, 50);
            rangeBars = marketDataService.fetchHistoricalBars(symbol, rangeFetch, rangeTf, sess);
            if (stTf != null) {
                int stFetch = autoCountOrb(stTf, targetDate, supertrend.length() + 10);
                stBars = marketDataService.fetchHistoricalBars(symbol, stFetch, stTf, sess);
            }
        } catch (MarketDataException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("webullError", e.toDetailMap());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }

        if (scanBars.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No market data available for ticker",
                    "ticker", symbol, "scanTimeframe", scanTf, "timestamp", TimeFormat.nowEt()));
        }

        OrbBacktester.Range range = OrbBacktester.openingRange(rangeBars, targetDate);

        OrbBacktester.Params params = new OrbBacktester.Params(
                orb.rangeMinutes(), orb.requireBreakoutCandleColor(),
                emaPeriod, orb.emaSlopeLookback(), orb.emaSlopeEnabled(),
                orb.stSlopeConfirmEnabled(), orb.stSlopeLookback(), orb.stSlopeMinAtr(), orb.stSlopeBars(),
                orb.up().enabled(), orb.down().enabled());

        List<OrbBacktester.Signal> signals = OrbBacktester.run(scanBars, rangeBars, stBars, targetDate, supertrend, params);

        Candle first = scanBars.get(0), last = scanBars.get(scanBars.size() - 1);
        LocalDate firstDate = first.timestamp().atZone(ET).toLocalDate();
        LocalDate lastDate = last.timestamp().atZone(ET).toLocalDate();
        boolean dateInRange = !targetDate.isBefore(firstDate) && !targetDate.isAfter(lastDate);
        long barsOnDate = scanBars.stream()
                .filter(c -> c.timestamp().atZone(ET).toLocalDate().equals(targetDate)).count();

        int upCount = 0, downCount = 0;
        List<String> audit = new ArrayList<>();
        for (OrbBacktester.Signal s : signals) {
            if (s.direction() == Direction.UP) upCount++; else downCount++;
            audit.add(auditLineOrb(s));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("date", targetDate.toString());
        body.put("rangeTimeframe", rangeTf);
        body.put("scanTimeframe", scanTf);
        if (stTf != null) body.put("stSlopeTimeframe", stTf);

        Map<String, Object> rangeMap = new LinkedHashMap<>();
        if (range != null) {
            rangeMap.put("high", range.high().toPlainString());
            rangeMap.put("low", range.low().toPlainString());
        } else {
            rangeMap.put("note", "opening range (09:30 " + rangeTf + " candle) not found for " + targetDate);
        }
        body.put("openingRange", rangeMap);

        body.put("signalCount", signals.size());
        body.put("orbUpCount", upCount);
        body.put("orbDownCount", downCount);
        body.put("signals", audit);

        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("requireBreakoutCandleColor", orb.requireBreakoutCandleColor());
        gates.put("emaSlopeEnabled", orb.emaSlopeEnabled());
        gates.put("emaPeriod", emaPeriod);
        gates.put("stSlopeConfirmEnabled", orb.stSlopeConfirmEnabled());
        body.put("gates", gates);

        if (debug) {
            List<OrbBacktester.Trace> traces = OrbBacktester.trace(scanBars, rangeBars, stBars, targetDate, supertrend, params);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (OrbBacktester.Trace t : traces) rows.add(traceRowOrb(t));
            body.put("debugTrace", rows);
        }

        Map<String, Object> warmup = new LinkedHashMap<>();
        warmup.put("scanBarsFetched", scanBars.size());
        warmup.put("barsOnTargetDate", barsOnDate);
        warmup.put("emaPeriod", emaPeriod);
        body.put("warmup", warmup);

        if (!dateInRange) {
            body.put("note", "Requested date is outside the fetched range (" + firstDate
                    + " .. " + lastDate + "). Increase ?count= to reach older dates.");
        } else if (barsOnDate == 0) {
            body.put("note", "No bars on " + targetDate + " (holiday/weekend or no trades).");
        } else if (range == null) {
            body.put("note", "No 09:30 ET opening candle on the " + rangeTf
                    + " range timeframe for " + targetDate + " — cannot compute the opening range.");
        }

        log.info("[Backtest/ORB] {} {} range={} scan={} → {} UP, {} DOWN (barsOnDate={}, fetched={})",
                symbol, targetDate, rangeTf, scanTf, upCount, downCount, barsOnDate, scanBars.size());

        return ResponseEntity.ok(body);
    }

    private Map<String, Object> traceRowOrb(OrbBacktester.Trace t) {
        Candle c = t.bar();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timeEt", OrbBacktester.timeEt(c));
        row.put("close", c.close().toPlainString());
        row.put("attempted", t.attempted() == null ? null : t.attempted().name());
        row.put("broke", t.broke());
        row.put("emaSlopeOk", t.emaSlopeOk());
        row.put("stSlopeOk", t.stSlopeOk());
        row.put("fired", t.fired());
        row.put("reason", t.reason());
        return row;
    }

    private String auditLineOrb(OrbBacktester.Signal s) {
        String emoji = s.direction() == Direction.UP ? "🟢" : "🔴";
        String price = s.bar().close().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String lvl = s.breakLevel().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return OrbBacktester.timeEt(s.bar()) + " ET  " + emoji + " " + s.direction()
                + "  @ " + price + "  (break " + (s.direction() == Direction.UP ? "> " : "< ") + lvl + ")";
    }

    /** Timeframe query override (validated) or the configured default. */
    private String resolveTf(String override, String fallback) {
        if (override != null && !override.isBlank()) {
            return MarketDataService.normaliseTimespan(override);
        }
        return fallback;
    }

    /** ORB fetch auto-sizing (handles native + synthetic M2/M3 via TimeframeSupport.minutesOf). */
    private int autoCountOrb(String timeframe, LocalDate targetDate, int warmupNeeded) {
        int tfMin = Math.max(0, TimeframeSupport.minutesOf(timeframe));
        long daysBack = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(targetDate, LocalDate.now(ET)));
        long tradingDays = (long) Math.ceil((daysBack + 1) * 7.0 / 5.0) + 2;
        long barsPerDay = tfMin > 0 ? Math.max(1, 390 / tfMin) : 1;
        long estimate = tradingDays * barsPerDay * 2 + warmupNeeded * 3L + 50;
        return (int) Math.min(Math.max(estimate, warmupNeeded + 50), 1200L);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", message, "timestamp", TimeFormat.nowEt()));
    }
}
