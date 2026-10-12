package com.trading.api;

import com.trading.alert.AlertHit;
import com.trading.alert.AlertMessageFormat;
import com.trading.config.AppProperties;
import com.trading.llm.AlertReviewContextBuilder;
import com.trading.llm.AlertReviewLog;
import com.trading.llm.AlertReviewVerdict;
import com.trading.llm.LlmAnalystService;
import com.trading.llm.LlmVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * REST API — on-demand LLM alert review (same engine the async pipeline uses).
 *
 * <p>Normally the review runs automatically when an alert fires (via the async
 * {@code AlertLlmReviewListener}). This endpoint runs the SAME path synchronously for an
 * arbitrary symbol/direction, so you can inspect and tune the multi-timeframe context + the
 * review verdict without waiting for a live alert. It also writes the same EOD log line +
 * JSON sidecar, so it is a faithful dry-run.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/llm/review/{ticker}</td>
 *       <td>Build multi-TF context, review the given direction, return + log the verdict</td></tr>
 * </table>
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code direction} — alert direction to review: UP/DOWN (default UP)</li>
 *   <li>{@code timeframe} — the notional alert timeframe label (default M5)</li>
 *   <li>{@code price}     — optional alert price label</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/llm")
public class AlertReviewController {

    private static final Logger log = LoggerFactory.getLogger(AlertReviewController.class);

    private final AppProperties props;
    private final AlertReviewContextBuilder contextBuilder;
    private final LlmAnalystService analyst;
    private final AlertReviewLog reviewLog;

    public AlertReviewController(AppProperties props, AlertReviewContextBuilder contextBuilder,
                                 LlmAnalystService analyst, AlertReviewLog reviewLog) {
        this.props = props;
        this.contextBuilder = contextBuilder;
        this.analyst = analyst;
        this.reviewLog = reviewLog;
    }

    @GetMapping("/review/{ticker}")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "UP") String direction,
            @RequestParam(defaultValue = "M5") String timeframe,
            @RequestParam(required = false) String price) {

        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        AlertMessageFormat.Dir dir;
        try {
            dir = AlertMessageFormat.Dir.valueOf(direction.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "direction must be UP or DOWN",
                    "timestamp", TimeFormat.nowEt()));
        }

        log.info("[AlertReviewController] GET /api/llm/review/{} direction={} timeframe={}",
                symbol, dir, timeframe);

        // Synthesise an AlertHit identical to what the engine would publish.
        java.math.BigDecimal p = null;
        if (price != null && !price.isBlank()) {
            try { p = new java.math.BigDecimal(price.trim()); } catch (NumberFormatException ignored) { }
        }
        String state = dir == AlertMessageFormat.Dir.UP ? "BULLISH" : "BEARISH";
        AlertHit hit = new AlertHit("manual-review", symbol, state,
                "manual review", dir, p, timeframe);

        // Build the multi-timeframe context (never throws).
        String context = contextBuilder.build(symbol);

        // Run the SAME review the async pipeline runs.
        AlertReviewVerdict verdict = analyst.reviewAlert(
                hit.alertId(), symbol, dir.name(), timeframe,
                p == null ? "" : p.toPlainString(), state, context);

        // Log it exactly as the async path would (line + JSON sidecar).
        reviewLog.record(hit, verdict, context);

        AppProperties.AlertReview cfg = props.alertReview();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", symbol);
        body.put("alertDirection", dir.name());
        body.put("timeframe", timeframe);
        body.put("model", cfg.model());
        body.put("reviewEnabled", cfg.enabled());
        body.put("context", context);
        body.put("verdict", toVerdictMap(verdict));
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toVerdictMap(AlertReviewVerdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conviction", v.conviction());
        m.put("confidence", v.confidence());
        m.put("alignment", v.alignment());
        m.put("supportingFactors", v.supportingFactors());
        m.put("riskFactors", v.riskFactors());
        m.put("rationale", v.rationale());

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
