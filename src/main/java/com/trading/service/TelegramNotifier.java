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
 * {@link NotificationService} backed by the Telegram Bot API
 * ({@code https://api.telegram.org/bot<token>/sendMessage}).
 *
 * <p>Active only when {@code webull.notifications.enabled=true},
 * {@code channel=TELEGRAM}, and a bot token + chat id are configured. Every send is
 * <b>fail-safe</b>: network/API errors are logged and swallowed so a notification
 * problem can never disrupt trading.</p>
 *
 * <p>Setup: create a bot with @BotFather to get the token; message the bot once, then
 * find your chat id (e.g. via @userinfobot). Put both in {@code webull.notifications.*}.</p>
 */
@Service
public class TelegramNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final WebullProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramNotifier(WebullProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isEnabled() {
        WebullProperties.Notifications n = props.notifications();
        return n != null
                && n.enabled()
                && "TELEGRAM".equalsIgnoreCase(n.channel())
                && n.telegramBotToken() != null && !n.telegramBotToken().isBlank()
                && n.telegramChatId() != null && !n.telegramChatId().isBlank();
    }

    @Override
    public void notify(String title, String message) {
        if (!isEnabled()) return;
        WebullProperties.Notifications n = props.notifications();
        String text = (title == null ? "" : title) + (message == null || message.isBlank() ? "" : "\n" + message);

        try {
            String url = "https://api.telegram.org/bot" + n.telegramBotToken().trim() + "/sendMessage";
            String payload = mapper.writeValueAsString(Map.of(
                    "chat_id", n.telegramChatId().trim(),
                    "text", text,
                    "disable_web_page_preview", true));
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    // Read a little of the body for the log, but never throw.
                    String body = resp.body() != null ? resp.body().string() : "";
                    log.warn("[TelegramNotifier] send failed: HTTP {} {}", resp.code(),
                            body.length() > 200 ? body.substring(0, 200) : body);
                }
            }
        } catch (Exception e) {
            // Fail-safe: a notification error must never affect trading.
            log.warn("[TelegramNotifier] send error (ignored): {}", e.getMessage());
        }
    }
}
