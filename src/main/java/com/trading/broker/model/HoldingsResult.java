package com.trading.broker.model;

import java.util.List;

/**
 * Broker-neutral result of a holdings/positions lookup: the list of holdings plus
 * whether the underlying broker call actually succeeded.
 *
 * <p>{@code valid=false} means the fetch FAILED — callers must not treat an empty
 * list as "flat" (no positions), because that would be indistinguishable from a
 * transient error. This distinction drives the no-short guard and position
 * reconciliation, so it must be preserved across broker adapters.</p>
 */
public record HoldingsResult(List<Holding> list, boolean valid) {

    /** A failed lookup: empty list, valid=false. */
    public static final HoldingsResult INVALID = new HoldingsResult(List.of(), false);
}
