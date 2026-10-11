package com.trading.api;

import com.trading.service.DesktopNotifier;
import com.trading.service.NotificationService;
import com.trading.service.TelegramChannelSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — send a TEST notification on demand, so you can verify the notifier
 * (token, chat id, connectivity) without waiting for a real trade.
 *
 * <table border="1">
 *   <tr><th>GET</th><td>/api/notifications/test</td>
 *       <td>Push a test message through the configured notifier, or to an explicit
 *           {@code chatId} (group/channel) using the shared bot token</td></tr>
 * </table>
 *
 * <p>Query params:</p>
 * <ul>
 *   <li>{@code message} — the body text to send.</li>
 *   <li>{@code chatId}  — optional. When set, the message is sent to THIS chat/channel id
 *       (e.g. {@code @mychannel}, a {@code -100...} group/channel id, or a user id) using
 *       the shared bot token — handy for verifying a separate channel such as the
 *       swift-scan channel. When omitted, the configured main notifier is used.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationTestController {

    private static final Logger log = LoggerFactory.getLogger(NotificationTestController.class);

    private final NotificationService notifier;
    private final TelegramChannelSender channelSender;
    private final DesktopNotifier desktop;

    public NotificationTestController(NotificationService notifier,
                                      TelegramChannelSender channelSender,
                                      DesktopNotifier desktop) {
        this.notifier = notifier;
        this.channelSender = channelSender;
        this.desktop = desktop;
    }

    /**
     * Fires a TEST native macOS desktop banner, so you can verify desktop notifications
     * without waiting for a live alert. No-op (reported) when the feature is disabled or not
     * on macOS. Banner body: {@code <alertType> <TICKER> <emoji> <Buy|Sell>}.
     *
     * <p>Query params (all optional, with sensible defaults):</p>
     * <ul>
     *   <li>{@code alertType} — e.g. "ORB UP", "Swift" (default "TEST")</li>
     *   <li>{@code ticker}    — symbol (default "AAPL")</li>
     *   <li>{@code direction} — UP or DOWN (default "UP" → 🟢 Buy)</li>
     * </ul>
     */
    @GetMapping("/test-desktop")
    public ResponseEntity<Map<String, Object>> testDesktop(
            @RequestParam(defaultValue = "orb-up") String alertType,
            @RequestParam(defaultValue = "AAPL") String ticker,
            @RequestParam(defaultValue = "UP") String direction,
            @RequestParam(defaultValue = "M5") String timeframe) {

        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = desktop.isEnabled();
        body.put("desktopEnabled", enabled);
        if (!enabled) {
            body.put("sent", false);
            body.put("hint", "Desktop notifications are off or not on macOS. Set "
                    + "app.notifications.desktop-enabled=true and run on the Mac GUI session.");
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        log.info("[NotificationTest] Firing test desktop banner: {} {} {} {}",
                timeframe, ticker, direction, alertType);
        desktop.notify(alertType, ticker, direction, timeframe);
        // Preview mirrors the actual banner line (type normalised: orb-up→ORB, swift-trend→Swift…).
        String typeLabel = alertType.toLowerCase().startsWith("orb") ? "ORB"
                : alertType.toLowerCase().startsWith("swift") ? "Swift"
                : (alertType.toLowerCase().startsWith("st-adx") || alertType.toLowerCase().startsWith("stadx")) ? "ST+ADX"
                : alertType;
        body.put("sent", true);
        body.put("preview", timeframe + " " + ticker + " "
                + ("UP".equalsIgnoreCase(direction) ? "🟢 BUY"
                   : "DOWN".equalsIgnoreCase(direction) ? "🔴 SELL" : direction)
                + " -- " + typeLabel);
        body.put("note", "Banner fired on the local Mac GUI session. Fail-safe — if nothing "
                + "appeared, check macOS notification permissions for your terminal/JVM.");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /**
     * Fires a TEST <b>burst</b> of desktop alerts to verify COALESCING: {@code count} simulated
     * alerts across a few sample tickers are sent as ONE batch, so you see the single summary
     * banner ("N alerts — AAPL 🟢Buy, NVDA 🔴Sell, ...") instead of N separate banners.
     *
     * @param count how many simulated alerts to include (default 3)
     */
    @GetMapping("/test-desktop-batch")
    public ResponseEntity<Map<String, Object>> testDesktopBatch(
            @RequestParam(defaultValue = "3") int count) {

        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = desktop.isEnabled();
        body.put("desktopEnabled", enabled);
        if (!enabled) {
            body.put("sent", false);
            body.put("hint", "Desktop notifications are off or not on macOS. Set "
                    + "app.notifications.desktop-enabled=true and run on the Mac GUI session.");
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        // Build a representative burst (alternating Buy/Sell across sample symbols/timeframes).
        String[] syms = {"AAPL", "NVDA", "PLTR", "TSLA", "AMD", "MSFT", "META", "AMZN"};
        String[] types = {"orb-up", "swift-trend", "orb-down", "st-adx"};
        String[] tfs = {"M5", "M3", "M15", "M1"};
        int n = Math.max(1, Math.min(count, 20));
        java.util.List<DesktopNotifier.Fired> burst = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String dir = (i % 2 == 0) ? "UP" : "DOWN";
            burst.add(new DesktopNotifier.Fired(types[i % types.length], syms[i % syms.length], dir, tfs[i % tfs.length]));
        }

        log.info("[NotificationTest] Firing test desktop BURST of {} alerts", n);
        desktop.notifyBatch(burst);
        body.put("sent", true);
        body.put("count", n);
        body.put("note", n == 1
                ? "Single alert → one normal banner."
                : "Burst of " + n + " → coalesced into ONE summary banner "
                    + "(\"Trading Alerts (" + n + ")\"). Individual alerts still go to Telegram/logs.");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam(defaultValue = "Test alert from the trading bot ✅") String message,
            @RequestParam(required = false) String chatId) {

        // Explicit chat/channel id → send via the shared bot token to that id directly.
        if (chatId != null && !chatId.isBlank()) {
            return testToChannel(chatId.trim(), message);
        }

        // Default path: use the configured main notifier.
        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = notifier.isEnabled();
        body.put("notifierEnabled", enabled);

        if (!enabled) {
            body.put("sent", false);
            body.put("hint", "Notifier is disabled or not fully configured. Check "
                    + "webull.notifications.enabled=true, channel=TELEGRAM, and that the "
                    + "bot token + chat id are set.");
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        log.info("[NotificationTest] Sending test notification");
        notifier.notify("🔔 Test Notification", message);
        // The notifier is fail-safe (errors are logged, not thrown). If a message did NOT
        // arrive, check the app logs for a [TelegramNotifier] warning (bad token/chat id).
        body.put("target", "configured notifier");
        body.put("sent", true);
        body.put("note", "Attempted send. If it didn't arrive, check app logs for a "
                + "[TelegramNotifier] warning (e.g. wrong token/chat id).");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /** Sends a test message to an explicit chat/channel id via the shared bot token. */
    private ResponseEntity<Map<String, Object>> testToChannel(String chatId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", "chatId " + chatId);

        if (!channelSender.isConfigured()) {
            body.put("sent", false);
            body.put("hint", "Bot token not configured. Set "
                    + "webull.notifications.telegram-bot-token (TELEGRAM_BOT_TOKEN).");
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);
        }

        log.info("[NotificationTest] Sending test notification to chatId={}", chatId);
        String text = "🔔 Test Notification\n" + message;
        boolean attempted = channelSender.send(chatId, text);

        body.put("sent", attempted);
        body.put("note", attempted
                ? "Attempted send to " + chatId + ". If it didn't arrive, check app logs for a "
                    + "[TelegramChannelSender] warning (e.g. wrong chat id, or the bot is not a "
                    + "member/admin of the group/channel)."
                : "Not sent — bot token or chatId missing.");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
