package com.trading.llm;

import com.trading.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for the parallel LLM alert review, so reviews run on their own small
 * thread pool — isolated from the alert scheduler and from Spring's default async executor.
 *
 * <p>Bounded + caller-runs rejection: if reviews pile up (LLM slower than alerts arrive) the
 * queue fills and excess work is simply dropped-to-caller rather than growing unbounded; the
 * review is best-effort analysis, never critical. {@code @EnableAsync} activates
 * {@code @Async("alertReviewExecutor")} on the listener.</p>
 */
@Configuration
@EnableAsync
public class AlertReviewAsyncConfig {

    public static final String EXECUTOR = "alertReviewExecutor";

    private final AppProperties props;

    public AlertReviewAsyncConfig(AppProperties props) {
        this.props = props;
    }

    @Bean(EXECUTOR)
    public Executor alertReviewExecutor() {
        int pool = Math.max(1, props.alertReview().poolSize());
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(pool);
        ex.setMaxPoolSize(pool);
        ex.setQueueCapacity(pool * 4);
        ex.setThreadNamePrefix("alert-review-");
        // If the queue is full, run on the publishing thread rather than failing — but since
        // the publisher is Spring's event multicaster (async path), this just applies gentle
        // backpressure instead of unbounded growth.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
