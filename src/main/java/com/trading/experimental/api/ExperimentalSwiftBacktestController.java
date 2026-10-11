package com.trading.experimental.api;

import com.trading.config.WebullProperties;
import com.trading.experimental.indicator.ExperimentalSwiftTrendScanner;
import com.trading.service.TimeframeSupport;
import com.trading.indicator.SupertrendCalculator.Direction;
import com.trading.indicator.SwiftTrendBacktester;
import com.trading.indicator.SwiftTrendBacktester.Signal;
import com.trading.indicator.SwiftTrendDetector.Result;
import com.trading.indicator.SwiftTrendDetector.Thresholds;
import com.trading.indicator.SwiftTrendScanner;
import com.trading.model.Candle;
import com.trading.service.MarketDataException;
import com.trading.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * EXPERIMENTAL side-by-side Swift Trend backtest. Runs the SAME historical candle series
 * through BOTH the production {@link SwiftTrendScanner} and the
 * {@link ExperimentalSwiftTrendScanner} (faster Supertrend + tuned thresholds) and returns
 * their signals together, so you can see whether the experimental config catches a move
 * earlier and how many extra (potentially false) signals it adds.
 *
 * <p>Reuses the production {@link SwiftTrendBacktester} (static, config-agnostic) and
 * {@link MarketDataService} verbatim — nothing in production code is modified. The only
 * difference between the two runs is which scanner (hence which length/factor/thresholds)
 * is passed.</p>
 *
 * <p>Endpoint: {@code GET /api/experimental/swift/backtest/{ticker}?date=YYYY-MM-DD&timeframe=M5}</p>
 */
@RestController
@RequestMapping("/api/experimental/swift")
public class ExperimentalSwiftBacktestController {

    private static final Logger log = LoggerFactory.getLogger(ExperimentalSwiftBacktestController.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final WebullProperties props;
    private final MarketDataService marketDataService;
    private final SwiftTrendScanner production;
    private final ExperimentalSwiftTrendScanner experimental;

    public ExperimentalSwiftBacktestController(
            WebullProperties props,
            MarketDataService marketDataService,
            SwiftTrendScanner production,
            @Qualifier("experimentalSwiftTrendScanner") ExperimentalSwiftTrendScanner experimental) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.production = production;
        this.experimental = experimental;
    }

    @GetMapping("/backtest/{ticker}")
    public ResponseEntity<Map<String, Object>> backtest(
            @PathVariable String ticker,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) String sessions,
            @RequestParam(required = false) Integer count) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ticker is required"));
        }

        LocalDate targetDate;
        try {
            targetDate = (date != null && !date.isBlank())
                    ? LocalDate.parse(date.trim(), DATE) : LocalDate.now(ET);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date '" + date + "' — use YYYY-MM-DD"));
        }

        // Timeframe: default to the experimental timeframe (so the comparison is apples-to-apples
        // on the experimental's chosen tf); callers can override. Synthetic minute timeframes
        // (M2/M3/…) are now handled by the PRODUCTION market-data layer (resampled from M1), so
        // this just normalises and fetches via MarketDataService like any native timeframe.
        String tfReq = (timeframe != null && !timeframe.isBlank())
                ? timeframe : experimental.config().timeframe();
        String ts;
        String sess;
        try {
            ts = MarketDataService.normaliseTimespan(tfReq);   // accepts native + synthetic M<n>
            String sessReq = (sessions != null && !sessions.isBlank())
                    ? sessions : props.trading().tradingSessions();
            sess = MarketDataService.normaliseSessions(sessReq);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedNativeTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "syntheticTimespans", "any M<n> minute form (e.g. M2, M3) resampled from M1",
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS));
        }

        // Size the fetch for the SLOWER scanner's warm-up (larger length needs more seed bars).
        int maxLength = Math.max(production.length(), experimental.length());
        int fetchCount = (count != null && count > 0) ? count : autoCount(resampleBase(ts), targetDate, maxLength);

        List<Candle> candles;
        try {
            // Production fetch is now M2/M3-aware (resamples synthetic timeframes from M1).
            candles = marketDataService.fetchHistoricalBars(symbol, fetchCount, ts, sess);
        } catch (MarketDataException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Market-data request failed", "ticker", symbol,
                    "webullError", e.toDetailMap()));
        }
        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No market data available", "ticker", symbol, "timespan", ts));
        }

        // The broker returns the most-recent N bars (no date-range query) and caps N at 1650.
        // On a fine/synthetic timeframe that window may not reach an older target date — detect
        // that and return a clear message instead of an empty/misleading comparison.
        LocalDate firstDate = candles.get(0).timestamp().atZone(ET).toLocalDate();
        LocalDate lastDate = candles.get(candles.size() - 1).timestamp().atZone(ET).toLocalDate();
        long barsOnDate = candles.stream()
                .filter(c -> c.timestamp().atZone(ET).toLocalDate().equals(targetDate))
                .count();
        if (barsOnDate == 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticker", symbol);
            body.put("date", targetDate.toString());
            body.put("timeframe", ts);
            body.put("barsFetched", candles.size());
            body.put("coveredFrom", firstDate.toString());
            body.put("coveredTo", lastDate.toString());
            if (targetDate.isBefore(firstDate)) {
                int minutes = Math.max(1, TimeframeSupport.minutesOf(ts));
                body.put("error", "Target date " + targetDate + " is older than the data window "
                        + "reachable in one request. The broker caps a bars request at "
                        + TimeframeSupport.BROKER_MAX_BARS + " bars, which on " + ts
                        + " (" + (TimeframeSupport.isSynthetic(ts) ? "resampled from " + minutes
                        + "×M1" : "native") + ") only reaches back to " + firstDate + ". Use a coarser "
                        + "timeframe, or pick a more recent date.");
            } else {
                body.put("error", "No bars on " + targetDate
                        + " (weekend/holiday, or the ticker had no trades in the requested sessions).");
            }
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }

        Map<String, Object> prodRun = runFor(production, candles, targetDate);
        Map<String, Object> expRun = runFor(experimental, candles, targetDate);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("date", targetDate.toString());
        body.put("timeframe", ts);
        body.put("sessions", sess);
        body.put("barsFetched", candles.size());
        body.put("coveredFrom", firstDate.toString());
        body.put("coveredTo", lastDate.toString());
        body.put("production", prodRun);
        body.put("experimental", expRun);
        body.put("comparison", compare(prodRun, expRun));

        log.info("[ExpSwiftBacktest] {} {} {} → prod {} signals (first {}), exp {} signals (first {})",
                symbol, targetDate, ts,
                prodRun.get("signalCount"), prodRun.get("firstSignalEt"),
                expRun.get("signalCount"), expRun.get("firstSignalEt"));

        return ResponseEntity.ok(body);
    }

    /** Replays the candle series through one scanner and summarises its signals. */
    private Map<String, Object> runFor(SwiftTrendScanner scanner, List<Candle> candles, LocalDate targetDate) {
        int length = scanner.length();
        BigDecimal factor = scanner.factor();
        Thresholds thresholds = scanner.thresholds(scanner.config());
        int cooldown = scanner.config().cooldownMinutes();

        List<Signal> signals = SwiftTrendBacktester.run(
                scanner, candles, targetDate, length, factor, thresholds, cooldown);

        int up = 0, down = 0;
        List<String> audit = new ArrayList<>(signals.size());
        String firstEt = null;
        for (Signal s : signals) {
            if (s.result().direction() == Direction.UP) up++; else down++;
            String line = auditLine(s);
            audit.add(line);
            if (firstEt == null) firstEt = line.substring(0, 5);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("supertrendLength", length);
        cfg.put("supertrendFactor", factor.toPlainString());
        cfg.put("minTravelAtr", thresholds.minTravelAtr().toPlainString());
        cfg.put("minSeparationAtr", thresholds.minSeparationAtr().toPlainString());
        cfg.put("cooldownMinutes", cooldown);
        out.put("config", cfg);
        out.put("signalCount", signals.size());
        out.put("swiftUpCount", up);
        out.put("swiftDownCount", down);
        out.put("firstSignalEt", firstEt);
        out.put("signals", audit);
        return out;
    }

    /** Highlights the earlier-entry delta and the extra-signal (false-positive risk) delta. */
    private Map<String, Object> compare(Map<String, Object> prod, Map<String, Object> exp) {
        Map<String, Object> c = new LinkedHashMap<>();
        String prodFirst = (String) prod.get("firstSignalEt");
        String expFirst = (String) exp.get("firstSignalEt");
        c.put("productionFirstSignalEt", prodFirst);
        c.put("experimentalFirstSignalEt", expFirst);
        if (prodFirst != null && expFirst != null) {
            long mins = minutesBetween(expFirst, prodFirst);
            c.put("experimentalEarlierByMinutes", mins);
        }
        int prodN = (int) prod.get("signalCount");
        int expN = (int) exp.get("signalCount");
        c.put("extraSignalsFromExperimental", expN - prodN);
        c.put("note", "Positive 'earlierByMinutes' = experimental fired sooner. "
                + "Positive 'extraSignals' = experimental produced more signals (earlier entry "
                + "traded for more potential false positives).");
        return c;
    }

    private long minutesBetween(String earlierHHmm, String laterHHmm) {
        String[] a = earlierHHmm.split(":");
        String[] b = laterHHmm.split(":");
        int ea = Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]);
        int la = Integer.parseInt(b[0]) * 60 + Integer.parseInt(b[1]);
        return la - ea;
    }

    private String auditLine(Signal s) {
        Result r = s.result();
        String emoji = r.direction() == Direction.UP ? "🟢" : "🔴";
        String timeEt = s.candle().timestamp().atZone(ET).format(HHMM);
        String st = r.supertrend() == null ? "n/a"
                : r.supertrend().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String price = r.close() == null ? "n/a"
                : r.close().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String travel = r.travelAtr() == null ? "n/a" : r.travelAtr().toPlainString();
        return timeEt + " ET  " + emoji + " " + r.direction()
                + "  @ " + price + "  ST " + st + "  travel " + travel + "×ATR";
    }

    /**
     * For sizing the fetch, treat a synthetic Mn as its own minute size when autoCount knows it,
     * otherwise fall back to M1 (the resampler fetches {@code count * minutes} M1 bars anyway, so
     * a slightly larger estimate just adds buffer). Keeps autoCount's switch simple.
     */
    private String resampleBase(String ts) {
        return switch (ts) {
            case "M1", "M5", "M15", "M30", "M60", "M120", "M240" -> ts;
            default -> "M1";   // synthetic M2/M3/… — size against M1
        };
    }

    private int autoCount(String timeframe, LocalDate targetDate, int length) {
        int tfMin = switch (timeframe) {
            case "M1" -> 1; case "M5" -> 5; case "M15" -> 15; case "M30" -> 30;
            case "M60" -> 60; case "M120" -> 120; case "M240" -> 240;
            default -> 0;
        };
        long daysBack = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(targetDate, LocalDate.now(ET)));
        long tradingDays = (long) Math.ceil((daysBack + 1) * 7.0 / 5.0) + 2;
        long barsPerDay = tfMin > 0 ? Math.max(1, 390 / tfMin) : 1;
        long estimate = tradingDays * barsPerDay * 2 + length * 3L + 50;
        long warmup = props.trading().warmupBars();
        long floored = Math.max(estimate, warmup);
        // Clamp to the broker's hard per-request cap so we never trip HTTP 417.
        return (int) Math.min(floored, TimeframeSupport.BROKER_MAX_BARS);
    }
}
