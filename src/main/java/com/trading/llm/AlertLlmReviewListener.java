package com.trading.llm;

import com.trading.alert.AlertHit;
import com.trading.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * The parallel LLM alert-review pipeline. Listens for {@link AlertReviewEvent}s published by
 * the alert engine and, on a dedicated async executor, gathers the distilled multi-timeframe
 * context, asks the LLM to measure conviction/confidence in the alert's direction, and logs
 * the result for end-of-day analysis.
 *
 * <p><b>Entirely off the critical path and fail-safe.</b> It runs after the alert has already
 * been delivered and logged; any error here is swallowed and never affects alert delivery.
 * Gated by {@code app.alert-review.enabled} and {@code app.llm.enabled}.</p>
 */
@Component
public class AlertLlmReviewListener {

    private static final Logger log = LoggerFactory.getLogger(AlertLlmReviewListener.class);

    private final AppProperties props;
    private final AlertReviewContextBuilder contextBuilder;
    private final LlmAnalystService analyst;
    private final AlertReviewLog reviewLog;

    public AlertLlmReviewListener(AppProperties props,
                                  AlertReviewContextBuilder contextBuilder,
                                  LlmAnalystService analyst,
                                  AlertReviewLog reviewLog) {
        this.props = props;
        this.contextBuilder = contextBuilder;
        this.analyst = analyst;
        this.reviewLog = reviewLog;
    }

    @Async(AlertReviewAsyncConfig.EXECUTOR)
    @EventListener
    public void onAlert(AlertReviewEvent event) {
        AlertHit hit = event == null ? null : event.hit();
        if (hit == null) return;

        try {
            if (!props.alertReview().enabled() || !props.llm().enabled()) return;

            long t0 = System.currentTimeMillis();
            String context = contextBuilder.build(hit.ticker());

            String alertDir = hit.direction() == null ? "" : hit.direction().name();
            String price = hit.price() == null ? "" : hit.price().toPlainString();

            AlertReviewVerdict verdict = analyst.reviewAlert(
                    hit.alertId(), hit.ticker(), alertDir,
                    hit.timeframe(), price, hit.state(), context);

            reviewLog.record(hit, verdict, context);

            long ms = System.currentTimeMillis() - t0;
            log.info("[AlertLlmReviewListener] reviewed {} {} → conviction={} confidence={} ({}ms)",
                    hit.alertId(), hit.ticker(), verdict.conviction(), verdict.confidence(), ms);
        } catch (Exception e) {
            // Never let a review failure escape — the alert already fired and was logged.
            log.warn("[AlertLlmReviewListener] review failed for {} {} (ignored): {}",
                    hit.alertId(), hit.ticker(), e.getMessage());
        }
    }
}
