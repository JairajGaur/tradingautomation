package com.trading.alert;

import com.trading.config.AppProperties;
import com.trading.indicator.SwiftTrendScanner;
import com.trading.indicator.SwiftTrendScanner.Decision;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.MarketDataService;
import com.trading.service.TelegramChannelSender;
import com.trading.service.TimeframeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Swift Trend Scanner alert. Scans the universe for a <b>fresh Supertrend direction flip
 * backed by strong, ATR-normalised momentum</b> (pure Supertrend + momentum, no EMA).
 *
 * <p>All detection + the "fire once per flip" cooldown live in the shared
 * {@link SwiftTrendScanner} — the SINGLE source of truth that the backtest also uses, so
 * what you backtest is exactly what fires live. This class is only the plumbing: it plugs
 * into {@link AlertEngine} for <b>scheduling, market-hours gating, and shared bar
 * prefetch</b>, and does its OWN delivery to a separate Telegram channel (which the
 * engine's shared notifier can't target), returning an EMPTY list so the engine dispatches
 * nothing further.</p>
 */
@Component
public class SwiftTrendAlert implements Alert {

    private static final Logger log = LoggerFactory.getLogger(SwiftTrendAlert.class);

    private final AppProperties props;
    private final BarDataManager barData;
    private final SwiftTrendScanner scanner;
    private final TelegramChannelSender telegram;
    private final com.trading.service.DesktopNotifier desktop;

    public SwiftTrendAlert(AppProperties props,
                           BarDataManager barData,
                           SwiftTrendScanner scanner,
                           TelegramChannelSender telegram,
                           com.trading.service.DesktopNotifier desktop) {
        this.props = props;
        this.barData = barData;
        this.scanner = scanner;
        this.telegram = telegram;
        this.desktop = desktop;
    }

    @Override
    public String id() { return "swift-trend"; }

    @Override
    public boolean isEnabled() {
        return cfg() != null && cfg().enabled();
    }

    @Override
    public String messagePrefix() {
        String p = cfg().messagePrefix();
        return p == null ? "" : p;
    }

    /**
     * Scan cadence is DERIVED from the configured timeframe so the two can never drift: a
     * new completed bar only appears once per timeframe interval, so scanning more often is
     * wasted work and scanning less often misses bars. M1 → every 1 min, M5 → every 5 min,
     * M15 → 15, etc. (Day/Week/etc. fall back to 1.)
     */
    @Override
    public int scanIntervalMinutes() {
        return Math.max(1, timeframeMinutes(timeframe()));
    }

    /**
     * Minutes per bar for a minute-timeframe; 1 for non-minute timeframes. Parses any
     * {@code M<n>} form generically via {@link TimeframeSupport} (single source of truth), so
     * synthetic timeframes (M2/M3/…) derive the correct cadence — e.g. M3 scans every 3 min,
     * matching when a new M3 bar completes — instead of defaulting to every minute.
     */
    private static int timeframeMinutes(String tf) {
        return Math.max(1, TimeframeSupport.minutesOf(tf));   // M3 → 3; D/W/M/Y (-1) → 1
    }

    private AppProperties.Alerts.SwiftScanCfg cfg() {
        return scanner.config();
    }

    private String timeframe() {
        return MarketDataService.normaliseTimespan(cfg().timeframe());
    }

    @Override
    public List<String> timeframesNeeded() {
        try { return List.of(timeframe()); }
        catch (Exception e) {
            log.warn("[SwiftTrendAlert] invalid timeframe '{}' — nothing to prefetch", cfg().timeframe());
            return List.of();
        }
    }

    /**
     * Evaluates the universe via the shared {@link SwiftTrendScanner} (detect + cooldown),
     * sends firing signals to the dedicated channel, and returns an EMPTY list
     * (self-delivery). The engine still handles scheduling, market-hours gating, and the
     * shared bar prefetch for {@link #timeframesNeeded()}.
     */
    @Override
    public List<AlertHit> evaluate(List<String> universe) {
        AppProperties.Alerts.SwiftScanCfg c = cfg();
        if (c == null) return List.of();

        final String tf;
        try { tf = timeframe(); }
        catch (IllegalArgumentException e) {
            log.warn("[SwiftTrendAlert] invalid timeframe config: {}", e.getMessage());
            return List.of();
        }

        String chatId = resolveChatId(c);
        if (chatId == null || chatId.isBlank()) {
            log.warn("[SwiftTrendAlert] no telegram chat id configured (swift-scan.telegram-chat-id "
                    + "and notifications.telegram-chat-id both blank) — skipping scan");
            return List.of();
        }
        if (!telegram.isConfigured()) {
            log.warn("[SwiftTrendAlert] telegram bot token not configured — skipping scan");
            return List.of();
        }

        int warmup = props.engine().warmupBars();
        long now = System.currentTimeMillis();
        // Collect this tick's fired swift signals for ONE coalesced desktop banner at the end.
        List<com.trading.service.DesktopNotifier.Fired> desktopBurst = new java.util.ArrayList<>();

        for (String symbol : universe) {
            try {
                List<Candle> bars = barData.getBars(symbol, tf, warmup);
                // Single source of truth: detection + cooldown both live in the scanner.
                Decision d = scanner.evaluate(symbol, bars, now);
                if (!d.fire()) continue;

                String text = scanner.formatMessage(c, symbol, d.result());
                telegram.send(chatId, text);
                // Uniform, machine-parseable fired-alert line — SAME shape as every other alert.
                AlertMessageFormat.Dir dir =
                        d.result().direction() == com.trading.indicator.SupertrendCalculator.Direction.UP
                                ? AlertMessageFormat.Dir.UP : AlertMessageFormat.Dir.DOWN;
                AlertLog.fired(id(), symbol, dir, d.result().close(), tf,
                        "flip@" + (d.result().flipTime() == null ? "?" : d.result().flipTime()),
                        d.reason());
                // Buffer for a single coalesced desktop banner at the end of the scan.
                desktopBurst.add(new com.trading.service.DesktopNotifier.Fired(id(), symbol, dir.name(), tf));
            } catch (Exception e) {
                log.warn("[SwiftTrendAlert] {} failed (ignored): {}", symbol, e.getMessage());
            }
        }
        // One coalesced desktop banner for the whole swift scan (fail-safe; no-op when disabled).
        desktop.notifyBatch(desktopBurst);
        // Self-delivered — nothing for the engine to dispatch.
        return List.of();
    }

    /** The swift-scan channel id, falling back to the main notifications chat id when blank. */
    private String resolveChatId(AppProperties.Alerts.SwiftScanCfg c) {
        if (c.telegramChatId() != null && !c.telegramChatId().isBlank()) {
            return c.telegramChatId().trim();
        }
        AppProperties.Notifications n = props.notifications();
        return (n != null && n.telegramChatId() != null) ? n.telegramChatId().trim() : null;
    }
}
