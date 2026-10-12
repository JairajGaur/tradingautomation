package com.trading.llm;

import com.trading.alert.AlertHit;

/**
 * Published by the {@code AlertEngine} the moment an alert is delivered, carrying the
 * {@link AlertHit} so the parallel LLM review can run ASYNCHRONOUSLY off the critical path.
 *
 * <p>Decoupling via an application event (rather than a direct call) means the engine never
 * waits on — or fails because of — the review: it fires the event and moves on. The async
 * listener does the slow work (data gathering + LLM) on its own executor.</p>
 *
 * @param hit the fired alert
 */
public record AlertReviewEvent(AlertHit hit) {}
