package com.trading.api;

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

    public NotificationTestController(NotificationService notifier,
                                      TelegramChannelSender channelSender) {
        this.notifier = notifier;
        this.channelSender = channelSender;
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
