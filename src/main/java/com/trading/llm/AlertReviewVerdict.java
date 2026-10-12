package com.trading.llm;

/**
 * The simple, day-trader-friendly result of an LLM review of a fired alert
 * (see {@code prompts/alert-review.txt}).
 *
 * <p>One question, plainly answered: does the multi-timeframe evidence back the alert's
 * stated direction, and how strongly? <b>Advisory only</b> — it never places or blocks an
 * order; it is logged for end-of-day analysis.</p>
 *
 * @param verdict    AGREE / NEUTRAL / DISAGREE — does the evidence support the alert direction?
 * @param conviction 1..maxConviction — how strong that judgment is
 * @param reason     one short line explaining the call
 * @param watchFor   the single thing that would change / confirm it next (may be blank)
 * @param ok         true when parsed from a real model reply; false for a fail-safe fallback
 * @param note       diagnostic note when {@code ok} is false; else blank
 */
public record AlertReviewVerdict(
        String verdict,
        int conviction,
        String reason,
        String watchFor,
        boolean ok,
        String note
) {
    /** Neutral fail-safe verdict when the LLM is disabled/down/returns garbage. */
    public static AlertReviewVerdict failSafe(String note) {
        return new AlertReviewVerdict("NEUTRAL", 1, "LLM review unavailable.", "",
                false, note == null ? "" : note);
    }
}
