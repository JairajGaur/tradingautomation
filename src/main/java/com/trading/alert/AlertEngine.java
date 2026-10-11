package com.trading.alert;

import com.trading.config.UniverseLoader;
import com.trading.config.AppProperties;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import com.trading.service.TelegramChannelSender;
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
 * Runs all enabled {@link Alert}s on a schedule over the ticker universe and posts each hit
 * to THAT ALERT'S OWN Telegram channel ({@link Alert#channelId()}) via the shared
 * {@link TelegramChannelSender}. There is no common/shared channel — channels may be the
 * same or different per alert, by config. One scheduler for every alert. Bars are
 * batch-prefetched once per needed timeframe via the shared {@link BarDataManager} — alerts
 * never fetch data separately.
 *
 * <p>Dedupe: a hit is only posted when a ticker's state for that alert CHANGES (keyed by
 * {@code alertId|ticker} → last state), so you aren't re-notified every cycle while the
 * condition holds. When a ticker stops matching, its state is cleared so the next match
 * re-alerts.</p>
 *
 * <p>Only runs within the configured market-hours window (weekdays), reusing
 * {@code MarketHoursGuard.isTradingAllowed()} — the same window the trading loop uses.</p>
 *
 * <p>Fully fail-safe — evaluation/delivery errors are logged, never thrown.</p>
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final AppProperties props;
    private final UniverseLoader universeLoader;
    private final BarDataManager barData;
    private final TelegramChannelSender telegram;
    private final com.trading.service.DesktopNotifier desktop;
    private final com.trading.service.MarketHoursGuard marketHoursGuard;
    private final List<Alert> alerts;

    /** Last posted state per "alertId|ticker" for dedupe. */
    private final Map<String, String> lastState = new ConcurrentHashMap<>();

    /** Last run epoch-minute per alert id — each alert runs on its OWN cadence. */
    private final Map<String, Long> lastRunByAlert = new ConcurrentHashMap<>();

    public AlertEngine(AppProperties props,
                       UniverseLoader universeLoader,
                       BarDataManager barData,
                       TelegramChannelSender telegram,
                       com.trading.service.DesktopNotifier desktop,
                       com.trading.service.MarketHoursGuard marketHoursGuard,
                       List<Alert> alerts) {
        this.props = props;
        this.universeLoader = universeLoader;
        this.barData = barData;
        this.telegram = telegram;
        this.desktop = desktop;
        this.marketHoursGuard = marketHoursGuard;
        this.alerts = alerts;
    }

    /** Fires every minute; runs each alert only when ITS OWN interval has elapsed. */
    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        try {
            AppProperties.Alerts cfg = props.alerts();
            if (cfg == null || !cfg.enabled()) return;
            if (!telegram.isConfigured()) return;   // no bot token — nowhere to post

            // Only alert within the configured market-hours window (weekdays), reusing the
            // same guard as the trading loop — one source of truth for "market hours".
            if (!marketHoursGuard.isTradingAllowed()) {
                log.debug("[AlertEngine] Outside market hours ({}) — skipping scan",
                        marketHoursGuard.currentSessionDescription());
                return;
            }

            // Which enabled alerts are DUE this minute (each on its own cadence)?
            long nowMin = System.currentTimeMillis() / 60_000L;
            List<Alert> due = new java.util.ArrayList<>();
            for (Alert a : alerts) {
                if (!a.isEnabled()) continue;
                Long last = lastRunByAlert.get(a.id());
                int every = Math.max(1, a.scanIntervalMinutes());
                if (last == null || nowMin - last >= every) {
                    due.add(a);
                    lastRunByAlert.put(a.id(), nowMin);
                }
            }
            if (due.isEmpty()) return;

            run(due);
        } catch (Exception e) {
            log.warn("[AlertEngine] tick failed (ignored): {}", e.getMessage());
        }
    }

    private void run(List<Alert> due) {
        List<String> universe = universeLoader.getUniverse();
        if (universe.isEmpty()) return;

        // Batch-prefetch only the timeframes the DUE alerts need — once, shared cache.
        int warmup = props.engine().warmupBars();
        Set<String> tfs = new LinkedHashSet<>();
        for (Alert a : due) {
            for (String tf : a.timeframesNeeded()) {
                try { tfs.add(MarketDataService.normaliseTimespan(tf)); } catch (Exception ignored) { }
            }
        }
        for (String tf : tfs) {
            try { barData.getBarsBatch(universe, tf, warmup); } catch (Exception e) {
                log.warn("[AlertEngine] prefetch {} failed: {}", tf, e.getMessage());
            }
        }

        // Collect desktop notifications across ALL alerts in this tick, then flush ONCE so a
        // burst (many alerts in one minute) coalesces into a single banner instead of spam.
        List<com.trading.service.DesktopNotifier.Fired> desktopBurst = new java.util.ArrayList<>();

        for (Alert a : due) {
            try {
                List<AlertHit> hits = a.evaluate(universe);
                // Each alert delivers to its OWN channel (no common channel). Resolve once per
                // alert; when blank, this alert has no destination so we skip its delivery.
                String channel = a.channelId();
                boolean hasChannel = channel != null && !channel.isBlank();
                if (!hits.isEmpty() && !hasChannel) {
                    log.warn("[AlertEngine] alert '{}' produced hits but has no telegram channel "
                            + "configured — skipping delivery", a.id());
                }
                Set<String> matchedKeys = new LinkedHashSet<>();
                for (AlertHit hit : hits) {
                    String key = hit.alertId() + "|" + hit.ticker();
                    matchedKeys.add(key);
                    String prev = lastState.get(key);
                    if (hit.state().equals(prev)) continue;   // unchanged → skip (dedupe)
                    lastState.put(key, hit.state());
                    if (!hasChannel) continue;   // nowhere to send (already warned above)
                    // hit.message() is the fully-formatted uniform single line
                    // (AlertMessageFormat) — send it as-is to the alert's own channel.
                    telegram.send(channel, hit.message());
                    // Uniform, machine-parseable fired-alert line (same shape for every alert).
                    AlertLog.fired(hit.alertId(), hit.ticker(), hit.direction(), hit.price(),
                            null, hit.state(), "channel=" + channel);
                    // Buffer for a single coalesced desktop banner at the end of the tick.
                    desktopBurst.add(new com.trading.service.DesktopNotifier.Fired(
                            hit.alertId(), hit.ticker(),
                            hit.direction() == null ? null : hit.direction().name(),
                            hit.timeframe()));
                }
                // For NON-latched alerts, clear dedupe for tickers that no longer match, so
                // a fresh recurrence re-alerts. (The ORB alerts are non-latched: a close back
                // inside the range clears the key, so each re-break re-alerts.) A LATCHED alert
                // instead keeps its state until it changes; if its state key embeds the date it
                // fires once per day and re-arms the next day. No alert currently sets
                // latched()=true, so this cleanup runs for all of them.
                if (!a.latched()) {
                    String alertPrefix = a.id() + "|";
                    lastState.keySet().removeIf(k -> k.startsWith(alertPrefix) && !matchedKeys.contains(k));
                }
            } catch (Exception e) {
                log.warn("[AlertEngine] alert '{}' failed (ignored): {}", a.id(), e.getMessage());
            }
        }

        // One coalesced desktop banner for the whole tick (1 line if a single alert, else a
        // "N alerts — ..." summary). Fail-safe; no-op when desktop notifications are disabled.
        desktop.notifyBatch(desktopBurst);
    }
}
