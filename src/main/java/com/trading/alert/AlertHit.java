package com.trading.alert;

/**
 * A single alert match for a ticker.
 *
 * @param alertId stable alert id (from {@link Alert#id()})
 * @param ticker  the symbol that matched
 * @param state   the matched state (e.g. "BULLISH" / "BEARISH") — also used for dedupe,
 *                so the engine re-notifies only when a ticker's state changes
 * @param message human-readable body for the notification
 */
public record AlertHit(String alertId, String ticker, String state, String message) {}
