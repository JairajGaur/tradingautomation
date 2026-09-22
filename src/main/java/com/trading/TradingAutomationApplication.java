package com.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.trading.config.WebullProperties;

import java.util.TimeZone;

/**
 * Entry point for the Webull automated trading bot.
 *
 * <h2>Timezone</h2>
 * The entire application runs in US Eastern time (America/New_York). The JVM's
 * default timezone is set to ET <b>before</b> the Spring context starts, so every
 * component — schedulers, strategies, controllers, logs, and any
 * {@code LocalDateTime}/{@code ZonedDateTime.now()} — operates in ET automatically.
 * ({@code America/New_York} handles the EST/EDT daylight-saving switch correctly.)
 *
 * <h2>Safety</h2>
 * Paper vs live is controlled by the single {@code webull.trading.mode} property
 * (PAPER = sandbox, LIVE = production). Defaults to PAPER.
 */
@SpringBootApplication
@EnableConfigurationProperties(WebullProperties.class)
@EnableScheduling
public class TradingAutomationApplication {

    /** Application-wide timezone — US Eastern (handles EST/EDT automatically). */
    public static final String APP_TIMEZONE = "America/New_York";

    public static void main(String[] args) {
        // Set the JVM default timezone to ET before anything else initialises.
        TimeZone.setDefault(TimeZone.getTimeZone(APP_TIMEZONE));
        System.setProperty("user.timezone", APP_TIMEZONE);

        SpringApplication.run(TradingAutomationApplication.class, args);
    }
}
