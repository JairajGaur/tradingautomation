package com.trading.experimental.config;

import com.trading.config.AppProperties;
import com.trading.experimental.indicator.ExperimentalSwiftTrendScanner;
import com.trading.indicator.SupertrendService;
import com.trading.indicator.SwiftTrendScanner;
import com.trading.service.MarketHoursGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;

/**
 * Wires the EXPERIMENTAL strategy sandbox into the Spring context WITHOUT touching the
 * production config:
 *
 * <ul>
 *   <li>{@link EnableConfigurationProperties} registers {@link ExperimentalSwiftProperties}
 *       (prefix {@code experimental.swift}). This is additive — the production
 *       {@code @EnableConfigurationProperties(AppProperties.class)} on the main app class
 *       is untouched, and both bind independently.</li>
 *   <li>{@link PropertySource} loads {@code classpath:application-experimental.yml} using a
 *       YAML-aware factory ({@link YamlPropertySourceFactory}, a small local helper) so the
 *       experimental knobs live in their OWN file. We do NOT use a Spring profile here on
 *       purpose: a profile ({@code application-experimental.yml} via
 *       {@code spring.profiles.active}) would override the shared {@code webull.*} beans
 *       globally, making it impossible to run production and experimental scanners
 *       side-by-side in one process. A separate {@code experimental.*} prefix keeps them
 *       isolated so a backtest can compare both at once.</li>
 * </ul>
 *
 * <p>Shared properties (sessions, market-hours, broker API, warm-up) are still read from the
 * production {@link com.trading.config.AppProperties} by the experimental beans — nothing
 * is duplicated.</p>
 */
@Configuration
@EnableConfigurationProperties(ExperimentalSwiftProperties.class)
@PropertySource(
        value = "classpath:application-experimental.yml",
        factory = YamlPropertySourceFactory.class,
        ignoreResourceNotFound = true)
public class ExperimentalPropertySourceConfig {

    /**
     * The experimental scanner bean. It is a normal autowire candidate, but adding a second
     * {@code SwiftTrendScanner}-typed bean would make existing production injections of the
     * base type ambiguous. To keep production untouched AND unambiguous, we mark the PRODUCTION
     * scanner as {@link Primary} via {@link #primarySwiftTrendScanner} below — so every existing
     * {@code SwiftTrendScanner} injection still resolves to the production bean, while this
     * experimental bean is reachable by its name/concrete type for the backtest controller.
     */
    @Bean(name = "experimentalSwiftTrendScanner")
    public ExperimentalSwiftTrendScanner experimentalSwiftTrendScanner(
            AppProperties props,
            SupertrendService supertrend,
            MarketHoursGuard marketHoursGuard,
            ExperimentalSwiftProperties exp) {
        return new ExperimentalSwiftTrendScanner(props, supertrend, marketHoursGuard, exp);
    }

    /**
     * Marks the PRODUCTION {@link SwiftTrendScanner} as the {@link Primary} bean for its type,
     * so introducing {@link #experimentalSwiftTrendScanner} (a second bean of that type) does
     * not break the existing production injection points (e.g. the live {@code SwiftTrendAlert}
     * and the production backtest controller), which inject the base type without a qualifier.
     *
     * <p>This returns the SAME production instance (injected by its component bean name
     * {@code swiftTrendScanner}); it does not create a second one. Nothing in production code
     * is modified — the primary designation lives entirely in this experimental config.</p>
     */
    @Bean
    @Primary
    public SwiftTrendScanner primarySwiftTrendScanner(
            @Qualifier("swiftTrendScanner") SwiftTrendScanner production) {
        return production;
    }
}
