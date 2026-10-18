package com.trading.option;

import com.trading.api.TimeFormat;
import com.trading.config.AppProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.indicator.SupertrendCalculator.Point;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.option.OptionContractService.LoadedContract;
import com.trading.option.OptionContractService.UnderlyingContracts;
import com.trading.service.MarketDataService;
import com.trading.webull.WebullApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API — OPTIONS data: generate OCC contracts from configured underlyings, load their
 * bars, inspect what's held in memory, and compute Supertrend / EMA on an option contract.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/options/load</td>
 *       <td>Generate OCC contracts for all configured underlyings and load their bars</td></tr>
 *   <tr><td>POST</td><td>/api/options/load/{underlying}</td>
 *       <td>Generate + load for one underlying</td></tr>
 *   <tr><td>GET</td><td>/api/options/contracts</td>
 *       <td>Inspect the OCC contracts loaded in memory (all underlyings)</td></tr>
 *   <tr><td>GET</td><td>/api/options/contracts/{underlying}</td>
 *       <td>Inspect the loaded contracts for one underlying</td></tr>
 *   <tr><td>GET</td><td>/api/options/history/{occSymbol}</td>
 *       <td>Historical OHLCV bars for one option contract (OCC code)</td></tr>
 *   <tr><td>GET</td><td>/api/options/supertrend/{occSymbol}</td>
 *       <td>Supertrend series for one option contract (reuses the stock calculator)</td></tr>
 *   <tr><td>GET</td><td>/api/options/ema/{occSymbol}</td>
 *       <td>Configured EMA values for one option contract</td></tr>
 * </table>
 *
 * <p>Underlyings come from {@code option-tickers.txt}; strike/expiry generation is driven by
 * {@code app.options.*}. A generated OCC that isn't actually listed is logged per-contract and
 * reported under {@code error} — it never fails the rest of the load. NOTE: option market data
 * requires a paid Webull market-data subscription — without it the broker returns 401/403,
 * surfaced here as a 502.</p>
 */
@RestController
@RequestMapping("/api/options")
public class OptionsController {

    private static final Logger log = LoggerFactory.getLogger(OptionsController.class);

    /** Webull's per-request cap for OPTION bars (count &gt; this → HTTP 417). */
    private static final int OPTION_BARS_MAX = 1200;

    private final AppProperties props;
    private final WebullApiProperties brokerApi;
    private final OptionBarDataManager barDataManager;
    private final OptionContractService optionContractService;
    private final OptionUniverseLoader underlyings;
    private final OptionVolumeStAlert volStAlert;
    private final MarketDataService marketDataService;
    private final SupertrendService supertrend;

    public OptionsController(AppProperties props, WebullApiProperties brokerApi,
                             OptionBarDataManager barDataManager,
                             OptionContractService optionContractService,
                             OptionUniverseLoader underlyings,
                             OptionVolumeStAlert volStAlert,
                             MarketDataService marketDataService,
                             SupertrendService supertrend) {
        this.props = props;
        this.brokerApi = brokerApi;
        this.barDataManager = barDataManager;
        this.optionContractService = optionContractService;
        this.underlyings = underlyings;
        this.volStAlert = volStAlert;
        this.marketDataService = marketDataService;
        this.supertrend = supertrend;
    }

    // -----------------------------------------------------------------------
    // Load — generate OCC contracts from underlyings and pull their bars
    // -----------------------------------------------------------------------

    /** Generates + loads option contracts for ALL configured underlyings. */
    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadAll() {
        log.info("[OptionsController] POST /api/options/load");
        List<UnderlyingContracts> results = optionContractService.loadAllConfigured();

        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> perUnderlying = results.stream()
                .map(this::underlyingSummary).collect(Collectors.toList());
        body.put("underlyings", results.size());
        body.put("totalContracts", results.stream().mapToInt(u -> u.contracts().size()).sum());
        body.put("totalLoaded", results.stream()
                .flatMap(u -> u.contracts().stream()).filter(LoadedContract::loaded).count());
        body.put("results", perUnderlying);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Generates + loads option contracts for ONE underlying. */
    @PostMapping("/load/{underlying}")
    public ResponseEntity<Map<String, Object>> loadOne(@PathVariable String underlying) {
        String sym = underlying == null ? "" : underlying.trim().toUpperCase();
        log.info("[OptionsController] POST /api/options/load/{}", sym);
        UnderlyingContracts uc = optionContractService.loadUnderlying(sym);
        return ResponseEntity.ok(underlyingDetail(uc));
    }

    // -----------------------------------------------------------------------
    // Inspect — the OCC contracts currently held in memory
    // -----------------------------------------------------------------------

    /** Lists the loaded contracts for every underlying held in memory (summary form). */
    @GetMapping("/contracts")
    public ResponseEntity<Map<String, Object>> listContracts() {
        Map<String, UnderlyingContracts> all = optionContractService.all();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("underlyings", all.size());
        body.put("results", all.values().stream().map(this::underlyingSummary).collect(Collectors.toList()));
        body.put("hint", all.isEmpty() ? "Nothing loaded yet — POST /api/options/load first." : null);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Full per-contract detail for one underlying held in memory. */
    @GetMapping("/contracts/{underlying}")
    public ResponseEntity<Map<String, Object>> getContracts(@PathVariable String underlying) {
        UnderlyingContracts uc = optionContractService.get(underlying);
        if (uc == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "No contracts loaded for underlying " + underlying,
                    "hint", "POST /api/options/load/" + (underlying == null ? "" : underlying.toUpperCase()) + " first.",
                    "timestamp", TimeFormat.nowEt()));
        }
        return ResponseEntity.ok(underlyingDetail(uc));
    }

    // -----------------------------------------------------------------------
    // Vol+ST alert — inspection (side gate) and backtest (dry-run of both legs)
    // -----------------------------------------------------------------------

    /**
     * Dry-run of the option-vol-st Supertrend alert WITHOUT delivery: for each underlying it loads
     * the near-the-money contracts (both sides) off the current price and evaluates every contract's
     * legs — ST direction/rising, close-above-EMA, and (optional) volume — reporting the metrics and
     * whether each qualifies. "What would fire right now."
     *
     * <p>{@code GET /api/options/vol-st/backtest} (all) or {@code ?underlying=AAPL}. Reads option
     * data through the cache. Note: options have no deep history (most-recent-N only), so this is a
     * live dry-run, not a historical replay.</p>
     */
    @GetMapping("/vol-st/backtest")
    public ResponseEntity<Map<String, Object>> volStBacktest(
            @RequestParam(required = false) String underlying) {

        List<String> syms = (underlying != null && !underlying.isBlank())
                ? List.of(underlying.trim().toUpperCase())
                : underlyings.getUnderlyings();

        AppProperties.Alerts.OptionVolStCfg c = props.alerts().optionVolSt();
        java.util.Set<OptionContract.Type> bothSides =
                java.util.EnumSet.of(OptionContract.Type.CALL, OptionContract.Type.PUT);
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalQualifying = 0;

        for (String u : syms) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("underlying", u);

            List<Map<String, Object>> contractRows = new ArrayList<>();
            UnderlyingContracts uc = optionContractService.loadUnderlying(u, bothSides);
            row.put("spot", uc.spot() == null ? null : uc.spot().toPlainString());
            if (uc.error() != null) row.put("note", uc.error());
            for (LoadedContract lc : uc.contracts()) {
                if (!lc.loaded()) continue;
                OptionVolumeStAlert.LegEval e = volStAlert.evaluateLegs(lc.contract());
                if (e.qualifies()) totalQualifying++;
                contractRows.add(legEvalMap(e));
            }
            row.put("contractsEvaluated", contractRows.size());
            row.put("stUpContracts", contractRows.stream().filter(m -> Boolean.TRUE.equals(m.get("stDirectionUp"))).count());
            row.put("qualifying", contractRows.stream().filter(m -> Boolean.TRUE.equals(m.get("qualifies"))).count());
            row.put("contracts", contractRows);
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timeframe", volStAlert.optionTimeframe());
        body.put("stRisingCandles", c.stRisingCandles());
        body.put("emaPeriod", c.emaPeriod());
        body.put("emaAboveCandles", c.emaAboveCandles());
        body.put("emaStackEnabled", c.emaStackEnabled());
        body.put("emaStackFast", c.emaStackFast());
        body.put("emaStackSlow", c.emaStackSlow());
        body.put("volumeEnabled", c.volumeEnabled());
        body.put("minSessionVolume", c.minSessionVolume());
        body.put("volumeLookback", c.volumeLookback());
        body.put("totalQualifying", totalQualifying);
        body.put("results", rows);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /**
     * INTRADAY REPLAY of the option-vol-st alert over the latest available RTH session (09:30–16:00 ET).
     * For each underlying it finds the stock's OPEN price (today's 09:30 bar), generates the
     * near-the-money contracts (both sides) off that open, fetches each contract's max M1 bars,
     * and walks them bar-by-bar — evaluating the legs on only the data up to each bar (no
     * lookahead), recording every fired signal with its timestamp and applying the configured
     * {@code cooldown-minutes} between fires on the same contract.
     *
     * <p>{@code GET /api/options/vol-st/replay} (all underlyings) or {@code ?underlying=AAPL}.
     * Uses the latest session with an opening bar in the returned stock history, including
     * the previous open session on weekends, holidays, and before today's open.</p>
     */
    @GetMapping("/vol-st/replay")
    public ResponseEntity<Map<String, Object>> volStReplay(
            @RequestParam(required = false) String underlying) {

        List<String> syms = (underlying != null && !underlying.isBlank())
                ? List.of(underlying.trim().toUpperCase())
                : underlyings.getUnderlyings();

        AppProperties.Alerts.OptionVolStCfg c = props.alerts().optionVolSt();
        String tf = volStAlert.optionTimeframe();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"));
        java.util.Set<OptionContract.Type> bothSides =
                java.util.EnumSet.of(OptionContract.Type.CALL, OptionContract.Type.PUT);

        // Resolve from actual opening bars: this handles weekends and holidays without
        // guessing a calendar date. Use one date across the entire response.
        Map<String, List<Candle>> stockBars = new LinkedHashMap<>();
        Map<String, String> stockErrors = new LinkedHashMap<>();
        for (String u : syms) {
            try {
                stockBars.put(u, marketDataService.fetchHistoricalBars(u, 1650, "M1", "RTH"));
            } catch (Exception e) {
                stockErrors.put(u, "stock history unavailable — cannot resolve replay session");
            }
        }
        java.time.LocalDate sessionDate = latestReplaySession(stockBars, today);
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalSignals = 0;

        for (String u : syms) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("underlying", u);

            // 1. Pick strikes from the resolved session's actual opening price.
            java.math.BigDecimal open = stockOpenPrice(stockBars.get(u), sessionDate);
            if (open == null) {
                row.put("note", stockErrors.getOrDefault(u,
                        "no 09:30 ET stock bar for the selected session in available history"));
                row.put("signals", List.of());
                rows.add(row);
                continue;
            }
            row.put("stockOpen", open.toPlainString());

            // 2. Generate contracts off the OPEN, fetch each contract's max bars, replay.
            List<OptionContract> generated = optionContractService.generateContracts(u, open, bothSides, sessionDate);
            List<Map<String, Object>> contractRows = new ArrayList<>();
            int sigCount = 0;
            int contractsWithSessionData = 0;
            for (OptionContract oc : generated) {
                List<Candle> bars;
                try {
                    bars = marketDataService.fetchOptionBars(oc.occSymbol(), OPTION_BARS_MAX, tf);
                } catch (Exception e) {
                    continue;   // unlisted/empty contract — skip (logged by the service)
                }
                if (bars == null || bars.isEmpty()) continue;
                OptionVolumeStAlert.ContractReplay rep = volStAlert.replayContract(oc, bars, sessionDate);
                if (rep.rthBars() > 0) contractsWithSessionData++;
                if (rep.signals().isEmpty()) continue;   // only report contracts that fired
                sigCount += rep.signals().size();
                contractRows.add(replayMap(rep));
            }
            totalSignals += sigCount;
            row.put("contractsWithSignals", contractRows.size());
            row.put("contractsWithSessionData", contractsWithSessionData);
            if (contractsWithSessionData == 0) {
                row.put("note", "no usable option bars for the selected session — signals could not be evaluated");
            }
            row.put("signalCount", sigCount);
            row.put("contracts", contractRows);
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", sessionDate == null ? null : sessionDate.toString());
        body.put("requestDate", today.toString());
        body.put("previousSession", sessionDate != null && sessionDate.isBefore(today));
        body.put("session", "RTH 09:30–16:00 ET (latest available session)");
        body.put("timeframe", tf);
        body.put("stRisingCandles", c.stRisingCandles());
        body.put("emaPeriod", c.emaPeriod());
        body.put("emaAboveCandles", c.emaAboveCandles());
        body.put("emaStackEnabled", c.emaStackEnabled());
        body.put("emaStackFast", c.emaStackFast());
        body.put("emaStackSlow", c.emaStackSlow());
        body.put("volumeEnabled", c.volumeEnabled());
        body.put("threeMinStEnabled", c.threeMinStEnabled());
        body.put("threeMinStTimeframe", c.threeMinStTimeframe());
        body.put("cooldownMinutes", c.cooldownMinutes());
        body.put("totalSignals", totalSignals);
        body.put("results", rows);
        body.put("note", "Strikes picked off the stock's OPEN price. Options have no deep dated "
                + "history (most-recent-N bars); replay uses the latest available stock opening session. "
                + "Option bars, including expired contracts, may be unavailable or incomplete. "
                + "Replay reports qualifying signals on a cooldown; live delivery dedupes "
                + "continuous matches until the condition clears. Strikes are fixed at the open "
                + "for replay, while live selection follows the current stock price.");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Latest actual opening session across the requested symbols; no calendar guessing. */
    static java.time.LocalDate latestReplaySession(Map<String, List<Candle>> histories,
                                                   java.time.LocalDate today) {
        java.time.LocalDate latest = null;
        java.time.ZoneId et = java.time.ZoneId.of("America/New_York");
        for (List<Candle> bars : histories.values()) {
            if (bars == null) continue;
            for (Candle bar : bars) {
                var time = bar.timestamp().atZone(et);
                var date = time.toLocalDate();
                if (time.toLocalTime().equals(java.time.LocalTime.of(9, 30))
                        && !date.isAfter(today) && (latest == null || date.isAfter(latest))) {
                    latest = date;
                }
            }
        }
        return latest;
    }

    private static BigDecimal stockOpenPrice(List<Candle> bars, java.time.LocalDate sessionDate) {
        if (bars == null || sessionDate == null) return null;
        java.time.ZoneId et = java.time.ZoneId.of("America/New_York");
        for (Candle bar : bars) {
            var time = bar.timestamp().atZone(et);
            if (time.toLocalDate().equals(sessionDate)
                    && time.toLocalTime().equals(java.time.LocalTime.of(9, 30))) return bar.open();
        }
        return null;
    }

    private static Map<String, Object> replayMap(OptionVolumeStAlert.ContractReplay r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("occSymbol", r.occSymbol());
        m.put("type", r.type());
        m.put("strike", r.strike());
        m.put("moneyness", r.moneyness());
        m.put("rthBars", r.rthBars());
        List<Map<String, Object>> sigs = new ArrayList<>();
        for (OptionVolumeStAlert.ReplaySignal s : r.signals()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("firedAtEt", s.firedAtEt());
            sm.put("optionClose", s.optionClose());
            sm.put("stRisingStreak", s.stRisingStreak());
            sm.put("aboveEma", s.aboveEma());
            sm.put("emaStackUp", s.emaStackUp());
            sm.put("volumeOk", s.volumeOk());
            sigs.add(sm);
        }
        m.put("signals", sigs);
        return m;
    }

    private static Map<String, Object> legEvalMap(OptionVolumeStAlert.LegEval e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("occSymbol", e.occSymbol());
        m.put("type", e.type());
        m.put("strike", e.strike());
        m.put("moneyness", e.moneyness());
        m.put("barsUsed", e.barsUsed());
        m.put("stDirectionUp", e.stDirectionUp());
        m.put("stRising", e.stRising());
        m.put("stRisingStreak", e.stRisingStreak());
        m.put("aboveEma", e.aboveEma());
        m.put("emaStackUp", e.emaStackUp());
        m.put("volumeOk", e.volumeOk());
        m.put("sessionVolume", e.sessionVolume());
        m.put("recentAvgVol", round1(e.recentAvgVol()));
        m.put("olderAvgVol", round1(e.olderAvgVol()));
        m.put("qualifies", e.qualifies());
        if (e.note() != null) m.put("note", e.note());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // -----------------------------------------------------------------------
    // Raw bars for one contract
    // -----------------------------------------------------------------------

    @GetMapping("/history/{occSymbol}")
    public ResponseEntity<Map<String, Object>> getOptionHistory(
            @PathVariable String occSymbol,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String timespan) {

        String symbol = occSymbol == null ? "" : occSymbol.trim().toUpperCase();
        int barCount = clampCount(count, props.options().barCount());

        String ts;
        try {
            ts = resolveTimespan(timespan);
        } catch (IllegalArgumentException e) {
            return badTimespan(e);
        }
        log.info("[OptionsController] GET /api/options/history/{} count={} timespan={}", symbol, barCount, ts);

        // Read through the cache (same model as stocks): cache hit when current for the
        // bar boundary, else a throttled fetch at app.options.timespan.
        List<Candle> candles = barDataManager.getBars(symbol, ts, barCount);
        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(noBarsBody(symbol, ts));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("optionSymbol", symbol);
        body.put("timespan", ts);
        body.put("count", candles.size());
        body.put("bars", toBarMaps(candles));
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Supertrend on an option contract — reuses the stock SupertrendService
    // -----------------------------------------------------------------------

    @GetMapping("/supertrend/{occSymbol}")
    public ResponseEntity<Map<String, Object>> getSupertrend(
            @PathVariable String occSymbol,
            @RequestParam(required = false) String timespan,
            @RequestParam(required = false) Integer count,
            @RequestParam(name = "bars", defaultValue = "50") int returnBars) {

        String symbol = occSymbol == null ? "" : occSymbol.trim().toUpperCase();
        int barCount = clampCount(count, props.options().barCount());
        int length = supertrend.length();

        String ts;
        try {
            ts = resolveTimespan(timespan);
        } catch (IllegalArgumentException e) {
            return badTimespan(e);
        }
        log.info("[OptionsController] GET /api/options/supertrend/{} length={} factor={} timespan={} count={}",
                symbol, length, supertrend.factor(), ts, barCount);

        List<Candle> candles = barDataManager.getBars(symbol, ts, barCount);

        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(noBarsBody(symbol, ts));
        }
        if (candles.size() < length) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Not enough bars (" + candles.size() + ") for length=" + length,
                    "timestamp", TimeFormat.nowEt()));
        }

        List<Point> points = supertrend.calculate(candles);
        int from = (returnBars > 0 && returnBars < points.size()) ? points.size() - returnBars : 0;
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = from; i < points.size(); i++) {
            Point p = points.get(i);
            Candle c = p.candle();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", c.timestamp().toString());
            row.put("timestampEt", TimeFormat.toEt(c.timestamp()));
            row.put("close", c.close().toPlainString());
            row.put("atr", p.atr() == null ? null : p.atr().toPlainString());
            row.put("supertrend", p.supertrend() == null ? null : p.supertrend().toPlainString());
            row.put("direction", p.direction() == null ? null : p.direction().name());
            row.put("flip", p.flip());
            series.add(row);
        }

        Point latest = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).supertrend() != null) { latest = points.get(i); break; }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("optionSymbol", symbol);
        body.put("length", length);
        body.put("factor", supertrend.factor().toPlainString());
        body.put("timespan", ts);
        body.put("barsFetched", candles.size());
        if (latest != null) {
            body.put("latestSupertrend", latest.supertrend().toPlainString());
            body.put("latestDirection", latest.direction().name());
            body.put("latestClose", latest.candle().close().toPlainString());
        }
        body.put("returned", series.size());
        body.put("series", series);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // EMA on an option contract — reuses the stock EmaCalculator
    // -----------------------------------------------------------------------

    @GetMapping("/ema/{occSymbol}")
    public ResponseEntity<Map<String, Object>> getEma(
            @PathVariable String occSymbol,
            @RequestParam(required = false) String timespan,
            @RequestParam(required = false) Integer count) {

        String symbol = occSymbol == null ? "" : occSymbol.trim().toUpperCase();
        int barCount = clampCount(count, props.options().barCount());

        String ts;
        try {
            ts = resolveTimespan(timespan);
        } catch (IllegalArgumentException e) {
            return badTimespan(e);
        }
        log.info("[OptionsController] GET /api/options/ema/{} timespan={} count={}", symbol, ts, barCount);

        List<Candle> candles = barDataManager.getBars(symbol, ts, barCount);

        if (candles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(noBarsBody(symbol, ts));
        }

        List<BigDecimal> closes = candles.stream().map(Candle::close).collect(Collectors.toList());
        List<Integer> periods = props.engine().emaPeriods();
        Map<String, String> emaValues = new LinkedHashMap<>();
        for (Integer period : periods) {
            if (period == null || period < 1) continue;
            if (closes.size() < period) {
                emaValues.put(String.valueOf(period), "INSUFFICIENT_DATA");
                continue;
            }
            try {
                BigDecimal ema = EmaCalculator.calculate(closes, period);
                emaValues.put(String.valueOf(period), ema.setScale(4, RoundingMode.HALF_UP).toPlainString());
            } catch (Exception e) {
                emaValues.put(String.valueOf(period), "ERROR");
                log.error("[OptionsController] Failed EMA({}) for {}", period, symbol, e);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("optionSymbol", symbol);
        body.put("timespan", ts);
        body.put("barsUsed", closes.size());
        body.put("lastClose", closes.get(closes.size() - 1).setScale(4, RoundingMode.HALF_UP).toPlainString());
        body.put("emaValues", emaValues);
        body.put("periods", periods);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Response shaping
    // -----------------------------------------------------------------------

    /** Compact per-underlying view (counts + the OCC list), for list endpoints. */
    private Map<String, Object> underlyingSummary(UnderlyingContracts uc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("underlying", uc.underlying());
        m.put("spot", uc.spot() == null ? null : uc.spot().toPlainString());
        m.put("expiry", uc.expiry() == null ? null : uc.expiry().toString());
        m.put("strikeIncrement", uc.strikeIncrement() == null ? null : uc.strikeIncrement().toPlainString());
        m.put("contracts", uc.contracts().size());
        m.put("loaded", uc.contracts().stream().filter(LoadedContract::loaded).count());
        m.put("occSymbols", uc.contracts().stream()
                .map(lc -> lc.contract().occSymbol()).collect(Collectors.toList()));
        if (uc.error() != null) m.put("error", uc.error());
        m.put("loadedAt", TimeFormat.toEt(uc.loadedAt()));
        return m;
    }

    /** Full per-underlying view with each contract's fields, bar count, and any error. */
    private Map<String, Object> underlyingDetail(UnderlyingContracts uc) {
        Map<String, Object> m = underlyingSummary(uc);
        m.remove("occSymbols");
        List<Map<String, Object>> contracts = new ArrayList<>();
        for (LoadedContract lc : uc.contracts()) {
            OptionContract c = lc.contract();
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("occSymbol", c.occSymbol());
            cm.put("type", c.type().name());
            cm.put("strike", c.strike().toPlainString());
            cm.put("moneyness", c.moneyness() == null ? null : c.moneyness().name());
            cm.put("expiry", c.expiry().toString());
            cm.put("barCount", lc.barCount());
            cm.put("loaded", lc.loaded());
            if (lc.error() != null) cm.put("error", lc.error());
            contracts.add(cm);
        }
        m.put("contractDetail", contracts);
        return m;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int clampCount(Integer count, int dflt) {
        int c = (count != null && count > 0) ? count : dflt;
        return Math.min(c, OPTION_BARS_MAX);
    }

    private ResponseEntity<Map<String, Object>> badTimespan(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                "timestamp", TimeFormat.nowEt()));
    }

    private static List<Map<String, Object>> toBarMaps(List<Candle> candles) {
        List<Map<String, Object>> bars = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("timestamp", c.timestamp().toString());
            bar.put("timestampEt", TimeFormat.toEt(c.timestamp()));
            bar.put("open", c.open().toPlainString());
            bar.put("high", c.high().toPlainString());
            bar.put("low", c.low().toPlainString());
            bar.put("close", c.close().toPlainString());
            bar.put("volume", c.volume());
            bars.add(bar);
        }
        return bars;
    }

    /**
     * Resolves the timespan for an option request: the caller's value when provided, else the
     * configured {@code app.options.timespan}. Validated against the supported native timespans.
     *
     * @throws IllegalArgumentException if the value is not a supported timespan
     */
    private String resolveTimespan(String requested) {
        String tf = (requested != null && !requested.isBlank()) ? requested : props.options().timespan();
        return MarketDataService.normaliseTimespan(tf);
    }

    /** Body for the "no bars" 503 — includes the subscription hint, since the most common
     *  cause offline is the missing US_OPTION entitlement (the broker error is logged by
     *  OptionBarDataManager). */
    private Map<String, Object> noBarsBody(String symbol, String ts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "No option bars for contract");
        body.put("optionSymbol", symbol);
        body.put("timespan", ts);
        body.put("mode", props.engine().mode().name());
        body.put("endpoint", brokerApi.resolvedApiHost());
        body.put("hint", "Empty bars usually mean the strike/expiry isn't a listed contract, or "
                + "the account lacks the US_OPTION market-data subscription. See the server log "
                + "for the broker's exact error.");
        body.put("timestamp", TimeFormat.nowEt());
        return body;
    }
}
