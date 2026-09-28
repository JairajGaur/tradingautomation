package com.trading.api;

import com.trading.service.NotificationService;
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
 *       <td>Push a test message through the configured notifier</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationTestController {

    private static final Logger log = LoggerFactory.getLogger(NotificationTestController.class);

    private final NotificationService notifier;

    public NotificationTestController(NotificationService notifier) {
        this.notifier = notifier;
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam(defaultValue = "Test alert from the trading bot ✅") String message) {

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
        body.put("sent", true);
        body.put("note", "Attempted send. If it didn't arrive, check app logs for a "
                + "[TelegramNotifier] warning (e.g. wrong token/chat id).");
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
