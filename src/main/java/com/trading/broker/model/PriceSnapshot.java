package com.trading.broker.model;

/**
 * Broker-neutral point-in-time price snapshot for a symbol, exposing the common
 * fields a price/quote endpoint surfaces. All fields are strings (as the broker
 * reports them) or {@code null} when the broker omits them — this mirrors the
 * pre-abstraction endpoint contract without leaking broker JSON.
 *
 * <p>Each broker adapter maps its own snapshot payload into this shape; the
 * parsing never leaks past the adapter.</p>
 */
public record PriceSnapshot(
        String price,
        String open,
        String high,
        String low,
        String preClose,
        String volume,
        String change,
        String changeRatio
) {
    /** True when the broker returned no usable snapshot for the symbol. */
    public boolean isEmpty() {
        return price == null && open == null && high == null && low == null
                && preClose == null && volume == null && change == null && changeRatio == null;
    }
}
