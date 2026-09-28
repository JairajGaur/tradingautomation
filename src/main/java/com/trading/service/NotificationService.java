package com.trading.service;

/**
 * Channel-agnostic push-notification sender. Implementations deliver a short message
 * to an external channel (Telegram, etc.). All implementations MUST be fail-safe — a
 * delivery error must never propagate or disrupt trading; it is logged and swallowed.
 */
public interface NotificationService {

    /**
     * Sends a notification. Never throws — on failure it logs and returns.
     *
     * @param title short headline (e.g. "BUY TQQQ")
     * @param message body text
     */
    void notify(String title, String message);

    /** Whether notifications are currently enabled/configured. */
    boolean isEnabled();
}
