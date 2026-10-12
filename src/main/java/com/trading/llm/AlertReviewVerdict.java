package com.trading.llm;

import java.util.List;

/**
 * The structured result of an LLM review of a fired alert (see {@code prompts/alert-review.txt}).
 *
 * <p>Scoped to one question: how much should a disciplined trader trust the alert's stated
 * direction, given the multi-timeframe context? <b>Advisory only</b> — it never places or
 * blocks an order; it is logged for end-of-day analysis.</p>
 *
 * @param conviction         1..maxConviction — strength of the evidence FOR the alert direction
 * @param confidence         HIGH / MEDIUM / LOW — how sure the model is, given data quality
 * @param alignment          ALIGNED / MIXED / CONFLICTING — how the timeframes relate to the
 *                           alert direction overall
 * @param supportingFactors  evidence for the alert direction (may be empty)
 * @param riskFactors        evidence against / things to watch (may be empty)
 * @param rationale          2-3 sentence explanation
 * @param nextDirections     forward-looking scenarios (reuses {@link LlmVerdict.Scenario})
 * @param ok                 true when parsed from a real model reply; false for a fail-safe
 * @param note               diagnostic note when {@code ok} is false; else blank
 */
public record AlertReviewVerdict(
        int conviction,
        String confidence,
        String alignment,
        List<String> supportingFactors,
        List<String> riskFactors,
        String rationale,
        List<LlmVerdict.Scenario> nextDirections,
        boolean ok,
        String note
) {
    /** Neutral fail-safe verdict when the LLM is disabled/down/returns garbage. */
    public static AlertReviewVerdict failSafe(String note) {
        return new AlertReviewVerdict(1, "LOW", "MIXED", List.of(), List.of(),
                "LLM review unavailable.", List.of(), false, note == null ? "" : note);
    }
}
