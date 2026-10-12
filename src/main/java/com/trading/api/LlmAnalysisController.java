package com.trading.api;

import com.trading.config.AppProperties;
import com.trading.llm.LlmAnalystService;
import com.trading.llm.LlmBriefing;
import com.trading.llm.LlmVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — inspect the local-LLM analyst's verdict on a hand-built signal briefing.
 *
 * <p>Mirrors the other inspection endpoints (e.g. {@code /api/supertrend/{ticker}}): it
 * exists to <b>validate and tune</b> the LLM layer in isolation before any trading logic
 * depends on it. It does not place orders and does not read live market data — you pass the
 * signal facts directly, so you can probe any scenario (clean trend, trap, chop) and watch
 * how the model scores it.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/llm/analyze/{ticker}</td>
 *       <td>Run the LLM analyst over the supplied facts and return its verdict</td></tr>
 * </table>
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code timeframe} — the timeframe the facts describe (default M5)</li>
 *   <li>{@code session}   — market session label (default RTH)</li>
 *   <li>{@code fact}      — repeatable; each occurrence is one plain-language signal fact,
 *       e.g. {@code ?fact=Swift Trend flipped BULLISH 3 bars ago&fact=ADX 28 and rising}</li>
 * </ul>
 *
 * <p>Requires {@code app.llm.enabled=true}; otherwise the verdict comes back as a
 * fail-safe WAIT with {@code ok=false} explaining why.</p>
 */
@RestController
@RequestMapping("/api/llm")
public class LlmAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisController.class);

    private final AppProperties props;
    private final LlmAnalystService analyst;

    public LlmAnalysisController(AppProperties props, LlmAnalystService analyst) {
        this.props = props;
        this.analyst = analyst;
    }

    @GetMapping("/analyze/{ticker}")
    public ResponseEntity<Map<String, Object>> analyze(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "M5") String timeframe,
            @RequestParam(defaultValue = "RTH") String session,
            @RequestParam(name = "fact", required = false) List<String> facts) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        List<String> factList = facts == null ? List.of() : facts;

        log.info("[LlmAnalysisController] GET /api/llm/analyze/{} timeframe={} session={} facts={}",
                symbol, timeframe, session, factList.size());

        LlmBriefing briefing = new LlmBriefing(symbol, timeframe, session, factList);
        LlmVerdict verdict = analyst.analyze(briefing);

        AppProperties.Llm cfg = props.llm();

        Map<String, Object> verdictMap = new LinkedHashMap<>();
        verdictMap.put("action", verdict.action().name());
        verdictMap.put("conviction", verdict.conviction());
        verdictMap.put("rationale", verdict.rationale());
        verdictMap.put("riskFlags", verdict.riskFlags());
        verdictMap.put("ok", verdict.ok());
        verdictMap.put("note", verdict.note());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("timeframe", timeframe);
        body.put("session", session);
        body.put("facts", factList);
        body.put("model", cfg.model());
        body.put("llmEnabled", cfg.enabled());
        body.put("verdict", verdictMap);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
