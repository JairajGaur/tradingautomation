package com.trading.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.alert.AlertHit;
import com.trading.api.TimeFormat;
import com.trading.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Writes the result of an LLM alert review in two forms, both aimed at end-of-day analysis:
 *
 * <ol>
 *   <li>A single machine-parseable log line prefixed {@code [ALERT_LLM_REVIEW]} (same spirit
 *       as {@link com.trading.alert.AlertLog}) — easy to grep/aggregate at EOD.</li>
 *   <li>Optionally, a per-review JSON sidecar under {@code app.alert-review.json-dir}, which
 *       captures the full structured verdict (factors, scenarios) plus the exact distilled
 *       context the model saw — so a review can be audited later.</li>
 * </ol>
 *
 * <p>Fully fail-safe: a logging/IO error is swallowed (warn only) and never propagates to the
 * review pipeline or the alert path.</p>
 */
@Component
public class AlertReviewLog {

    private static final Logger line = LoggerFactory.getLogger("ALERT_LLM_REVIEW");
    private static final Logger log = LoggerFactory.getLogger(AlertReviewLog.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ET);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ET);

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlertReviewLog(AppProperties props) {
        this.props = props;
    }

    /**
     * Records one review. Emits the structured line always; writes a JSON sidecar when
     * {@code app.alert-review.log-json} is true. Never throws.
     *
     * @param hit     the alert that was reviewed
     * @param verdict the LLM's structured verdict (or fail-safe)
     * @param context the exact distilled multi-timeframe context the model saw
     */
    public void record(AlertHit hit, AlertReviewVerdict verdict, String context) {
        try {
            emitLine(hit, verdict);
        } catch (Exception e) {
            log.warn("[AlertReviewLog] failed to emit log line (ignored): {}", e.getMessage());
        }
        if (props.alertReview().logJson()) {
            try {
                writeJson(hit, verdict, context);
            } catch (Exception e) {
                log.warn("[AlertReviewLog] failed to write JSON sidecar (ignored): {}", e.getMessage());
            }
        }
    }

    private void emitLine(AlertHit hit, AlertReviewVerdict v) {
        StringBuilder sb = new StringBuilder("[ALERT_LLM_REVIEW]");
        sb.append(" id=").append(hit.alertId());
        sb.append(" ticker=").append(hit.ticker());
        if (hit.direction() != null) sb.append(" alertDir=").append(hit.direction());
        if (hit.timeframe() != null && !hit.timeframe().isBlank()) sb.append(" tf=").append(hit.timeframe());
        sb.append(" conviction=").append(v.conviction());
        sb.append(" confidence=").append(v.confidence());
        sb.append(" alignment=").append(v.alignment());
        sb.append(" ok=").append(v.ok());
        if (!v.nextDirections().isEmpty()) {
            LlmVerdict.Scenario top = v.nextDirections().get(0);
            sb.append(" nextDir=").append(top.direction()).append('/').append(top.likelihood());
        }
        if (v.rationale() != null && !v.rationale().isBlank()) {
            sb.append(" reason=\"").append(v.rationale().replace('"', '\'')).append('"');
        }
        line.info(sb.toString());
    }

    private void writeJson(AlertHit hit, AlertReviewVerdict v, String context) throws IOException {
        Path dir = Path.of(props.alertReview().jsonDir());
        Files.createDirectories(dir);

        Instant now = Instant.now();
        String file = FILE_TS.format(now) + "-" + safe(hit.alertId()) + "-" + safe(hit.ticker()) + ".json";

        ObjectNode root = mapper.createObjectNode();
        root.put("timestamp", TimeFormat.nowEt());
        root.put("day", DAY.format(now));

        ObjectNode alert = root.putObject("alert");
        alert.put("id", hit.alertId());
        alert.put("ticker", hit.ticker());
        alert.put("direction", hit.direction() == null ? null : hit.direction().name());
        alert.put("timeframe", hit.timeframe());
        alert.put("price", hit.price() == null ? null : hit.price().toPlainString());
        alert.put("state", hit.state());

        ObjectNode verdict = root.putObject("verdict");
        verdict.put("conviction", v.conviction());
        verdict.put("confidence", v.confidence());
        verdict.put("alignment", v.alignment());
        verdict.put("rationale", v.rationale());
        verdict.put("ok", v.ok());
        verdict.put("note", v.note());
        putStrings(verdict.putArray("supportingFactors"), v.supportingFactors());
        putStrings(verdict.putArray("riskFactors"), v.riskFactors());
        ArrayNode dirs = verdict.putArray("nextDirections");
        for (LlmVerdict.Scenario s : v.nextDirections()) {
            ObjectNode d = dirs.addObject();
            d.put("direction", s.direction());
            d.put("trigger", s.trigger());
            d.put("rationale", s.rationale());
            d.put("likelihood", s.likelihood());
        }

        root.put("context", context);
        root.put("model", props.alertReview().model());

        Path out = dir.resolve(file);
        Files.write(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        log.debug("[AlertReviewLog] wrote {}", out);
    }

    private static void putStrings(ArrayNode arr, Iterable<String> values) {
        for (String s : values) arr.add(s);
    }

    private static String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
