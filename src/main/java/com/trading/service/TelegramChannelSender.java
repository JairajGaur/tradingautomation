package com.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.WebullProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * The single, reusable low-level Telegram sender: posts a message to ANY chat id using the
 * shared bot token ({@code webull.notifications.telegram-bot-token}). This is the one place
 * that talks to the Telegram Bot API's {@code sendMessage}.
 *
 * <p>Callers:</p>
 * <ul>
 *   <li>{@link TelegramNotifier} — the {@code NotificationService} for the MAIN chat
 *       ({@code notifications.telegram-chat-id}); delegates its HTTP send here.</li>
 *   <li>Features needing their OWN channel — e.g. the Swift Trend Scanner posting to
 *       {@code swift-scan.telegram-chat-id}.</li>
 * </ul>
 *
 * <p>Every send is <b>fail-safe</b>: network/API errors are logged and swallowed so a
 * notification problem can never disrupt scanning or trading.</p>
 */
@Service
public class TelegramChannelSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelSender.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final WebullProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramChannelSender(WebullProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Whether the shared bot token is configured. The chat id is supplied per-send, so
     * the caller is responsible for having a non-blank target.
     */
    public boolean isConfigured() {
        WebullProperties.Notifications n = props.notifications();
        return n != null
                && n.telegramBotToken() != null
                && !n.telegramBotToken().isBlank();
    }

    /**
     * Sends {@code text} to {@code chatId} via the shared bot. No-op (returns false) when
     * the bot token or {@code chatId} is missing. Never throws.
     *
     * @param chatId Telegram chat/channel id (@name, -100..., or a user id)
     * @param text   message body
     * @return true if the send was attempted (token + chat id present), false otherwise
     */
    public boolean send(String chatId, String text) {
        if (!isConfigured() || chatId == null || chatId.isBlank()) return false;
        String token = props.notifications().telegramBotToken().trim();
        try {
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String payload = mapper.writeValueAsString(Map.of(
                    "chat_id", chatId.trim(),
                    "text", text == null ? "" : text,
                    "disable_web_page_preview", true));
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    log.warn("[TelegramChannelSender] send failed: HTTP {} {}", resp.code(),
                            body.length() > 200 ? body.substring(0, 200) : body);
                }
            }
            return true;
        } catch (Exception e) {
            // Fail-safe: a notification error must never affect scanning/trading.
            log.warn("[TelegramChannelSender] send error (ignored): {}", e.getMessage());
            return true;
        }
    }
}
