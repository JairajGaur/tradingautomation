package com.trading.api;

import com.trading.config.AppProperties;
import com.trading.llm.LlmAnalystService;
import com.trading.llm.LlmVerdict;
import com.trading.llm.MarketBriefingBuilder;
import com.trading.model.Candle;
import com.trading.service.MarketDataException;
import com.trading.service.MarketDataService;
import com.trading.webull.WebullApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — full LLM market evaluation for a symbol + timeframe.
 *
 * <p>Unlike {@code /api/llm/analyze/{ticker}} (which takes hand-typed facts), this endpoint
 * does the data work for you: it fetches historical bars, computes the Supertrend series,
 * distils them into a compact briefing (via {@link MarketBriefingBuilder}), and asks the LLM
 * for a verdict <b>and</b> a forward-looking read of the next likely directions.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/llm/evaluate/{ticker}</td>
 *       <td>Fetch bars + Supertrend, feed to the LLM, return verdict + scenarios</td></tr>
 * </table>
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code timeframe} — M1/M5/M15/M30/M60/M120/M240 (or synthetic M2/M3); default M5</li>
 *   <li>{@code count}     — bars to fetch (default 300; capped by the broker's 1650)</li>
 *   <li>{@code sessions}  — RTH/PRE/ATH/OVN (default: configured trading sessions)</li>
 * </ul>
 *
 * <p><b>Advisory only.</b> The verdict and scenarios are opinions — nothing here places an
 * order. If the LLM is disabled/unreachable the briefing is still returned and the verdict
 * is a fail-safe WAIT (ok=false).</p>
 */
@RestController
@RequestMapping("/api/llm")
public class MarketAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisController.class);

    private static final int DEFAULT_COUNT = 300;

    private final AppProperties props;
    private final WebullApiProperties brokerApi;
    private final MarketBriefingBuilder briefingBuilder;
    private final LlmAnalystService analyst;

    public MarketAnalysisController(AppProperties props, WebullApiProperties brokerApi,
                                    MarketBriefingBuilder briefingBuilder, LlmAnalystService analyst) {
        this.props = props;
        this.brokerApi = brokerApi;
        this.briefingBuilder = briefingBuilder;
        this.analyst = analyst;
    }

    @GetMapping("/evaluate/{ticker}")
    public ResponseEntity<Map<String, Object>> evaluate(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "M5") String timeframe,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String sessions) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        int barCount = count != null && count > 0 ? count : DEFAULT_COUNT;

        log.info("[MarketAnalysisController] GET /api/llm/evaluate/{} timeframe={} count={} sessions={}",
                symbol, timeframe, barCount, sessions);

        // Validate timespan / sessions up front (same pattern as SupertrendController).
        String ts;
        String sess;
        try {
            ts = MarketDataService.normaliseTimespan(timeframe);
            sess = MarketDataService.normaliseSessions(sessions);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "supportedTimespans", MarketDataService.SUPPORTED_TIMESPANS,
                    "supportedSessions", MarketDataService.SUPPORTED_SESSIONS,
                    "timestamp", TimeFormat.nowEt()));
        }

        String sessionLabel = sess != null ? sess : props.engine().tradingSessions();

        // Fetch + distil into a briefing.
        MarketBriefingBuilder.Result result;
        try {
            result = briefingBuilder.build(symbol, barCount, ts, sess, sessionLabel);
        } catch (MarketDataException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Market-data request failed");
            body.put("ticker", symbol);
            body.put("mode", props.engine().mode().name());
            body.put("endpoint", brokerApi.resolvedApiHost());
            body.put("webullError", e.toDetailMap());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        } catch (IllegalStateException e) {
            // No data / not enough bars for Supertrend.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", e.getMessage(),
                    "ticker", symbol,
                    "timeframe", ts,
                    "timestamp", TimeFormat.nowEt()));
        }

        // Ask the LLM (never throws — fails safe to WAIT).
        LlmVerdict verdict = analyst.analyze(result.briefing());

        // Build response.
        AppProperties.Llm cfg = props.llm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("timeframe", ts);
        body.put("sessions", sessionLabel);
        body.put("barsFetched", result.candles().size());
        body.put("model", cfg.model());
        body.put("llmEnabled", cfg.enabled());
        body.put("briefingFacts", result.briefing().facts());
        body.put("verdict", toVerdictMap(verdict));
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toVerdictMap(LlmVerdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", v.action().name());
        m.put("conviction", v.conviction());
        m.put("rationale", v.rationale());
        m.put("riskFlags", v.riskFlags());

        List<Map<String, Object>> dirs = new ArrayList<>();
        for (LlmVerdict.Scenario s : v.nextDirections()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("direction", s.direction());
            d.put("trigger", s.trigger());
            d.put("rationale", s.rationale());
            d.put("likelihood", s.likelihood());
            dirs.add(d);
        }
        m.put("nextDirections", dirs);

        m.put("ok", v.ok());
        m.put("note", v.note());
        return m;
    }
}
