package com.trading.llm;

import java.util.List;

/**
 * The parsed, validated result of an LLM analysis of an {@link LlmBriefing}.
 *
 * <p><b>Advisory only.</b> A verdict never places an order. It is an opinion the risk/exit
 * layer may act on and can always override. The {@link Action} is a recommendation, and
 * {@code conviction} is a 1..maxConviction score.</p>
 *
 * @param action         recommended action (ENTER / WAIT / SKIP)
 * @param conviction     confidence score, 1..maxConviction (higher = stronger)
 * @param rationale      one short sentence explaining the call
 * @param riskFlags      zero or more concerns the model surfaced (e.g. "earlier whipsaw")
 * @param nextDirections forward-looking "what could happen next" scenarios the model sees,
 *                       each a {@link Scenario} (likely path + the trigger that confirms it).
 *                       Advisory context for the human, NOT instructions to the engine.
 * @param ok             true when this verdict came from a successful, parsed model response;
 *                       false when it is a fail-safe fallback (LLM disabled/down/bad output)
 * @param note           diagnostic note (error summary) when {@code ok} is false; else blank
 */
public record LlmVerdict(
        Action action,
        int conviction,
        String rationale,
        List<String> riskFlags,
        List<Scenario> nextDirections,
        boolean ok,
        String note
) {
    public enum Action { ENTER, WAIT, SKIP }

    /**
     * A single forward-looking scenario — one way price could resolve from here.
     *
     * @param direction  the move's bias: "BULLISH", "BEARISH", or "SIDEWAYS"
     * @param trigger    the observable condition that would confirm this path
     *                   (e.g. "close back above the Supertrend line")
     * @param rationale  one short sentence on why this path is plausible
     * @param likelihood rough odds the model assigns: "HIGH", "MEDIUM", or "LOW"
     */
    public record Scenario(String direction, String trigger, String rationale, String likelihood) {}

    /**
     * The neutral fail-safe verdict. Returned whenever the LLM cannot produce a trusted
     * answer, so callers can treat "no opinion" uniformly without null checks.
     */
    public static LlmVerdict failSafe(String note) {
        return new LlmVerdict(Action.WAIT, 1, "LLM unavailable — defaulting to WAIT.",
                List.of(), List.of(), false, note == null ? "" : note);
    }
}
