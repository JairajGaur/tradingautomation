package com.trading.llm;

import java.util.List;

/**
 * A broker-neutral, indicator-neutral <b>briefing</b> handed to the LLM analyst.
 *
 * <p>This is the contract between the deterministic layer (indicators, alerts) and the
 * reasoning layer. It contains <em>facts the Java code already computed</em> — never raw
 * price arrays and never anything the model would have to do arithmetic on. The model's
 * job is judgment over these facts, not calculation.</p>
 *
 * <p>Every field is a short, human-readable fact. Keep it small: a compact briefing is
 * cheaper, faster, and gives the model less room to hallucinate. Add fields only when a
 * new fact genuinely changes the decision.</p>
 *
 * @param ticker          symbol under evaluation (e.g. "SPY")
 * @param timeframe       the bar timeframe the signals were computed on (e.g. "M5")
 * @param session         market session at evaluation time (e.g. "RTH", "PRE", "ATH")
 * @param facts           ordered list of plain-language signal facts, e.g.
 *                        "Swift Trend flipped BULLISH 3 bars ago",
 *                        "ADX 28 and rising", "Price 0.4 ATR above Supertrend line".
 *                        These come straight from your detectors/calculators.
 */
public record LlmBriefing(
        String ticker,
        String timeframe,
        String session,
        List<String> facts
) {
    /** Renders the briefing as the compact text block embedded in the prompt. */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ticker: ").append(ticker == null ? "?" : ticker).append('\n');
        sb.append("Timeframe: ").append(timeframe == null ? "?" : timeframe).append('\n');
        sb.append("Session: ").append(session == null ? "?" : session).append('\n');
        sb.append("Signals:\n");
        if (facts == null || facts.isEmpty()) {
            sb.append("  (none provided)\n");
        } else {
            for (String f : facts) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        return sb.toString();
    }
}
