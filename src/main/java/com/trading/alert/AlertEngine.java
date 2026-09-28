package com.trading.alert;

import com.trading.config.UniverseLoader;
import com.trading.config.WebullProperties;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import com.trading.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs all enabled {@link Alert}s on a schedule over the ticker universe and posts hits
 * to the notification channel. One scheduler for every alert. Bars are batch-prefetched
 * once per needed timeframe via the shared {@link BarDataManager} — alerts never fetch
 * data separately.
 *
 * <p>Dedupe: a hit is only posted when a ticker's state for that alert CHANGES (keyed by
 * {@code alertId|ticker} → last state), so you aren't re-notified every cycle while the
 * condition holds. When a ticker stops matching, its state is cleared so the next match
 * re-alerts. Every message is prefixed with {@code webull.alerts.message-prefix}.</p>
 *
 * <p>Only runs within the configured market-hours window (weekdays), reusing
 * {@code MarketHoursGuard.isTradingAllowed()} — the same window the trading loop uses.</p>
 *
 * <p>Fully fail-safe — evaluation/delivery errors are logged, never thrown.</p>
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final WebullProperties props;
    private final UniverseLoader universeLoader;
    private final BarDataManager barData;
    private final NotificationService notifier;
    private final com.trading.service.MarketHoursGuard marketHoursGuard;
    private final List<Alert> alerts;

    /** Last posted state per "alertId|ticker" for dedupe. */
    private final Map<String, String> lastState = new ConcurrentHashMap<>();

    private long lastRunEpochMin = 0;

    public AlertEngine(WebullProperties props,
                       UniverseLoader universeLoader,
                       BarDataManager barData,
                       NotificationService notifier,
                       com.trading.service.MarketHoursGuard marketHoursGuard,
                       List<Alert> alerts) {
        this.props = props;
        this.universeLoader = universeLoader;
        this.barData = barData;
        this.notifier = notifier;
        this.marketHoursGuard = marketHoursGuard;
        this.alerts = alerts;
    }

    /** Fires every minute; runs the scan only when the configured interval has elapsed. */
    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        try {
            WebullProperties.Alerts cfg = props.alerts();
            if (cfg == null || !cfg.enabled()) return;
            if (!notifier.isEnabled()) return;   // nowhere to post

            // Only alert within the configured market-hours window (weekdays), reusing the
            // same guard as the trading loop — one source of truth for "market hours".
            if (!marketHoursGuard.isTradingAllowed()) {
                log.debug("[AlertEngine] Outside market hours ({}) — skipping scan",
                        marketHoursGuard.currentSessionDescription());
                return;
            }

            int everyMin = Math.max(1, cfg.scanMinutes());
            long nowMin = System.currentTimeMillis() / 60_000L;
            if (nowMin - lastRunEpochMin < everyMin) return;
            lastRunEpochMin = nowMin;

            run(cfg);
        } catch (Exception e) {
            log.warn("[AlertEngine] tick failed (ignored): {}", e.getMessage());
        }
    }

    private void run(WebullProperties.Alerts cfg) {
        List<String> universe = universeLoader.getUniverse();
        if (universe.isEmpty()) return;

        List<Alert> enabled = alerts.stream().filter(Alert::isEnabled).toList();
        if (enabled.isEmpty()) return;

        // Batch-prefetch every timeframe any enabled alert needs — once, shared cache.
        int warmup = props.trading().warmupBars();
        Set<String> tfs = new LinkedHashSet<>();
        for (Alert a : enabled) {
            for (String tf : a.timeframesNeeded()) {
                try { tfs.add(MarketDataService.normaliseTimespan(tf)); } catch (Exception ignored) { }
            }
        }
        for (String tf : tfs) {
            try { barData.getBarsBatch(universe, tf, warmup); } catch (Exception e) {
                log.warn("[AlertEngine] prefetch {} failed: {}", tf, e.getMessage());
            }
        }

        for (Alert a : enabled) {
            try {
                String prefix = a.messagePrefix() == null ? "" : a.messagePrefix();   // per-alert prefix
                List<AlertHit> hits = a.evaluate(universe);
                Set<String> matchedKeys = new LinkedHashSet<>();
                for (AlertHit hit : hits) {
                    String key = hit.alertId() + "|" + hit.ticker();
                    matchedKeys.add(key);
                    String prev = lastState.get(key);
                    if (hit.state().equals(prev)) continue;   // unchanged → skip (dedupe)
                    lastState.put(key, hit.state());
                    notifier.notify(prefix + hit.state() + " " + hit.ticker(), hit.message());
                    log.info("[AlertEngine] alert={} {} -> {}", hit.alertId(), hit.ticker(), hit.state());
                }
                // Clear dedupe for this alert's tickers that no longer match, so a future
                // match re-alerts. Only touch keys belonging to THIS alert.
                String alertPrefix = a.id() + "|";
                lastState.keySet().removeIf(k -> k.startsWith(alertPrefix) && !matchedKeys.contains(k));
            } catch (Exception e) {
                log.warn("[AlertEngine] alert '{}' failed (ignored): {}", a.id(), e.getMessage());
            }
        }
    }
}
