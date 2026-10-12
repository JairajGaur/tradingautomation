package com.trading.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the JSON <b>schemas</b> that constrain the LLM's output for each use case, giving a
 * Jev-like "typed decision" contract: instead of asking the model nicely for JSON and hoping,
 * the runtime (Ollama) forces the reply to match the schema — right types, enum values, and
 * required fields. The caller then validates/clamps and maps into the domain records.
 *
 * <p>Schemas are built as Jackson nodes with the shared {@link ObjectMapper} from
 * {@link LlmClient}, so there is no new dependency and nothing to parse from a file.</p>
 */
final class LlmSchemas {

    private LlmSchemas() {}

    /**
     * Schema for the analyze verdict:
     * {@code {action, conviction, rationale, risk_flags[], next_directions[]}}.
     */
    static ObjectNode analyzeSchema(ObjectMapper m, int maxConviction) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.set("action", enumProp(m, "ENTER", "WAIT", "SKIP"));
        props.set("conviction", intProp(m, 1, maxConviction));
        props.set("rationale", strProp(m));
        props.set("risk_flags", strArrayProp(m));
        props.set("next_directions", scenariosProp(m));

        schema.set("required", m.createArrayNode()
                .add("action").add("conviction").add("rationale")
                .add("risk_flags").add("next_directions"));
        return schema;
    }

    /**
     * Schema for the SIMPLE day-trade alert-review verdict:
     * {@code {verdict, conviction, reason, watch_for}}.
     */
    static ObjectNode reviewSchema(ObjectMapper m, int maxConviction) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.set("verdict", enumProp(m, "AGREE", "NEUTRAL", "DISAGREE"));
        props.set("conviction", intProp(m, 1, maxConviction));
        props.set("reason", strProp(m));
        props.set("watch_for", strProp(m));

        schema.set("required", m.createArrayNode()
                .add("verdict").add("conviction").add("reason").add("watch_for"));
        return schema;
    }

    // ── primitive builders ──────────────────────────────────────────────────

    private static ObjectNode strProp(ObjectMapper m) {
        return m.createObjectNode().put("type", "string");
    }

    private static ObjectNode intProp(ObjectMapper m, int min, int max) {
        ObjectNode n = m.createObjectNode();
        n.put("type", "integer");
        n.put("minimum", min);
        n.put("maximum", max);
        return n;
    }

    private static ObjectNode enumProp(ObjectMapper m, String... values) {
        ObjectNode n = m.createObjectNode();
        n.put("type", "string");
        ArrayNode e = n.putArray("enum");
        for (String v : values) e.add(v);
        return n;
    }

    private static ObjectNode strArrayProp(ObjectMapper m) {
        ObjectNode n = m.createObjectNode();
        n.put("type", "array");
        n.set("items", strProp(m));
        return n;
    }

    /** Array of scenario objects {direction(enum), trigger, rationale, likelihood(enum)}. */
    private static ObjectNode scenariosProp(ObjectMapper m) {
        ObjectNode arr = m.createObjectNode();
        arr.put("type", "array");
        ObjectNode item = m.createObjectNode();
        item.put("type", "object");
        ObjectNode p = item.putObject("properties");
        p.set("direction", enumProp(m, "BULLISH", "BEARISH", "SIDEWAYS"));
        p.set("trigger", strProp(m));
        p.set("rationale", strProp(m));
        p.set("likelihood", enumProp(m, "HIGH", "MEDIUM", "LOW"));
        item.set("required", m.createArrayNode()
                .add("direction").add("trigger").add("rationale").add("likelihood"));
        arr.set("items", item);
        return arr;
    }
}
