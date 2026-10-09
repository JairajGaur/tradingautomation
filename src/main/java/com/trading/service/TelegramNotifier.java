package com.trading.service;

import com.trading.config.WebullProperties;
import org.springframework.stereotype.Service;

/**
 * {@link NotificationService} that delivers to the configured MAIN Telegram chat
 * ({@code webull.notifications.telegram-chat-id}).
 *
 * <p>Active only when {@code webull.notifications.enabled=true}, {@code channel=TELEGRAM},
 * and a bot token + chat id are configured. The actual Telegram HTTP send is delegated to
 * the reusable {@link TelegramChannelSender} (single source of truth for talking to the
 * Telegram Bot API) — this class only resolves the main chat id and the enabled state. Every
 * send is <b>fail-safe</b>: errors are logged and swallowed so a notification problem can
 * never disrupt trading.</p>
 *
 * <p>Setup: create a bot with @BotFather to get the token; message the bot once, then find
 * your chat id (e.g. via @userinfobot). Put both in {@code webull.notifications.*}.</p>
 */
@Service
public class TelegramNotifier implements NotificationService {

    private final WebullProperties props;
    private final TelegramChannelSender sender;

    public TelegramNotifier(WebullProperties props, TelegramChannelSender sender) {
        this.props = props;
        this.sender = sender;
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
        String text = (title == null ? "" : title)
                + (message == null || message.isBlank() ? "" : "\n" + message);
        sender.send(props.notifications().telegramChatId().trim(), text);
    }
}
