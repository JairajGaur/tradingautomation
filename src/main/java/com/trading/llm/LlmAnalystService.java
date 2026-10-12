package com.trading.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The advisory "analyst": turns a deterministic {@link LlmBriefing} into an
 * {@link LlmVerdict} using the local LLM.
 *
 * <p>This is where the system prompt (the model's job description and the strict output
 * contract) lives, and where the model's reply is parsed and validated. Every failure
 * mode — LLM disabled, unreachable, slow, non-JSON, out-of-range values — collapses to a
 * neutral fail-safe {@code WAIT} verdict. The caller therefore never has to care whether
 * the LLM was healthy; it just reads a verdict.</p>
 *
 * <p><b>Boundary:</b> this service produces an opinion only. It does not touch positions,
 * orders, or risk state. Wiring a verdict into entries/exits is the caller's job, and the
 * existing risk/exit gates keep final authority.</p>
 */
@Service
public class LlmAnalystService {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalystService.class);

    private final AppProperties props;
    private final LlmClient client;
    private final PromptTemplates prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmAnalystService(AppProperties props, LlmClient client, PromptTemplates prompts) {
        this.props = props;
        this.client = client;
        this.prompts = prompts;
    }

    /**
     * Analyse a briefing and return an advisory verdict. Never throws — on any problem it
     * returns {@link LlmVerdict#failSafe(String)}.
     */
    public LlmVerdict analyze(LlmBriefing briefing) {
        AppProperties.Llm cfg = props.llm();
        if (!cfg.enabled()) {
            return LlmVerdict.failSafe("LLM disabled (app.llm.enabled=false)");
        }
        if (briefing == null) {
            return LlmVerdict.failSafe("null briefing");
        }

        String prompt = buildPrompt(briefing, cfg.maxConviction());
        String raw;
        try {
            // Schema-constrained ("typed") output: the runtime forces the reply to match the
            // analyze schema, so parsing below is validation/clamping, not hope-it-parses.
            raw = client.generateSchema(prompt,
                    LlmSchemas.analyzeSchema(client.mapper(), cfg.maxConviction()), null, 0);
        } catch (RuntimeException e) {
            log.warn("[LlmAnalystService] LLM call failed for {} — failing safe: {}",
                    briefing.ticker(), e.getMessage());
            return LlmVerdict.failSafe(e.getMessage());
        }

        try {
            return parse(raw, cfg.maxConviction());
        } catch (RuntimeException e) {
            log.warn("[LlmAnalystService] could not parse LLM reply for {} — failing safe. reply={}",
                    briefing.ticker(), abbreviate(raw));
            return LlmVerdict.failSafe("unparseable reply: " + e.getMessage());
        }
    }

    /**
     * Builds the full prompt from the external {@code prompts/analyze.txt} template (role +
     * strict JSON contract) with the briefing substituted in. Keeping the text out of code
     * lets it be tuned without recompiling.
     */
    String buildPrompt(LlmBriefing briefing, int maxConviction) {
        return prompts.render("analyze", java.util.Map.of(
                "maxConviction", Integer.toString(maxConviction),
                "briefing", briefing.toPromptBlock()));
    }

    /** Parses the model's JSON reply into a validated verdict. Throws on malformed input. */
    LlmVerdict parse(String raw, int maxConviction) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }

        String actionStr = text(node, "action").toUpperCase().strip();
        LlmVerdict.Action action;
        try {
            action = LlmVerdict.Action.valueOf(actionStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad action: '" + actionStr + "'");
        }

        int conviction = node.path("conviction").asInt(0);
        if (conviction < 1) conviction = 1;
        if (conviction > maxConviction) conviction = maxConviction;

        String rationale = text(node, "rationale");

        List<String> flags = new ArrayList<>();
        JsonNode f = node.get("risk_flags");
        if (f != null && f.isArray()) {
            for (JsonNode el : f) {
                String s = el.asText("").strip();
                if (!s.isEmpty()) flags.add(s);
            }
        }

        return new LlmVerdict(action, conviction, rationale, List.copyOf(flags),
                parseScenarios(node.get("next_directions")), true, "");
    }

    // -----------------------------------------------------------------------
    // Alert review (parallel pipeline) — uses the external alert-review prompt
    // -----------------------------------------------------------------------

    /**
     * Reviews a fired alert against a distilled multi-timeframe context and returns a
     * structured {@link AlertReviewVerdict} measuring conviction/confidence in the alert's
     * direction. Never throws — any failure returns {@link AlertReviewVerdict#failSafe}.
     *
     * <p>Uses the {@code app.alert-review} model (default {@code qwen3:14b}) and a longer
     * timeout, since it runs off the critical path.</p>
     *
     * @param alertId        the fired alert's id
     * @param ticker         symbol
     * @param alertDirection stated direction (e.g. "BULLISH"/"UP"); may be blank
     * @param alertTimeframe the alert's timeframe
     * @param alertPrice     price at the alert (plain string); may be blank
     * @param alertState     the alert's state string
     * @param context        the distilled multi-timeframe context block
     */
    public AlertReviewVerdict reviewAlert(String alertId, String ticker, String alertDirection,
                                          String alertTimeframe, String alertPrice,
                                          String alertState, String context) {
        AppProperties.Llm llm = props.llm();
        AppProperties.AlertReview cfg = props.alertReview();
        if (!llm.enabled()) {
            return AlertReviewVerdict.failSafe("LLM disabled (app.llm.enabled=false)");
        }

        String prompt = prompts.render("alert-review", java.util.Map.ofEntries(
                java.util.Map.entry("alertId", nz(alertId)),
                java.util.Map.entry("ticker", nz(ticker)),
                java.util.Map.entry("alertDirection", nz(alertDirection)),
                java.util.Map.entry("alertTimeframe", nz(alertTimeframe)),
                java.util.Map.entry("alertPrice", nz(alertPrice)),
                java.util.Map.entry("alertState", nz(alertState)),
                java.util.Map.entry("context", nz(context)),
                java.util.Map.entry("maxConviction", Integer.toString(llm.maxConviction()))));

        // The review runs off the critical path — give it a generous timeout (3x the live one).
        String model = cfg.model();
        int timeout = Math.max(60, llm.timeoutSeconds() * 3);

        String raw;
        try {
            // Schema-constrained ("typed") output for the review verdict shape.
            raw = client.generateSchema(prompt,
                    LlmSchemas.reviewSchema(client.mapper(), llm.maxConviction()), model, timeout);
        } catch (RuntimeException e) {
            log.warn("[LlmAnalystService] alert review LLM call failed for {} — failing safe: {}",
                    ticker, e.getMessage());
            return AlertReviewVerdict.failSafe(e.getMessage());
        }

        try {
            return parseReview(raw, llm.maxConviction());
        } catch (RuntimeException e) {
            log.warn("[LlmAnalystService] could not parse alert-review reply for {} — failing safe. reply={}",
                    ticker, abbreviate(raw));
            return AlertReviewVerdict.failSafe("unparseable reply: " + e.getMessage());
        }
    }

    /** Parses the simple day-trade alert-review reply into a validated verdict. */
    AlertReviewVerdict parseReview(String raw, int maxConviction) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }

        int conviction = node.path("conviction").asInt(0);
        if (conviction < 1) conviction = 1;
        if (conviction > maxConviction) conviction = maxConviction;

        String verdict = text(node, "verdict").toUpperCase();
        if (!verdict.equals("AGREE") && !verdict.equals("DISAGREE")) verdict = "NEUTRAL";

        return new AlertReviewVerdict(
                verdict,
                conviction,
                text(node, "reason"),
                text(node, "watch_for"),
                true, "");
    }

    // -----------------------------------------------------------------------
    // Shared JSON helpers
    // -----------------------------------------------------------------------

    /** Parses a {@code next_directions} array into scenarios (shared by both verdict types). */
    private List<LlmVerdict.Scenario> parseScenarios(JsonNode nd) {
        List<LlmVerdict.Scenario> directions = new ArrayList<>();
        if (nd != null && nd.isArray()) {
            for (JsonNode el : nd) {
                if (el == null || !el.isObject()) continue;
                String dir = text(el, "direction").toUpperCase();
                String trigger = text(el, "trigger");
                String why = text(el, "rationale");
                String likelihood = text(el, "likelihood").toUpperCase();
                if (dir.isEmpty() && trigger.isEmpty() && why.isEmpty()) continue;
                directions.add(new LlmVerdict.Scenario(dir, trigger, why, likelihood));
            }
        }
        return List.copyOf(directions);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").strip();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
