package com.trading.broker;

import com.trading.broker.model.AccountBalance;
import com.trading.broker.model.ConnectivityStatus;
import com.trading.broker.model.HoldingsResult;
import com.trading.broker.model.OrderAck;
import com.trading.broker.model.OrderRequest;
import com.trading.broker.model.OrderSummary;
import com.trading.broker.model.PriceSnapshot;
import com.trading.broker.model.Quote;
import com.trading.model.Candle;

import java.util.List;
import java.util.Map;

/**
 * Broker-neutral, low-level client for the operations the trading application
 * performs against a brokerage API: account discovery, balances, positions,
 * historical bars, real-time snapshots, and order placement/history.
 *
 * <p>This interface is the seam that makes the application broker-agnostic. All
 * services (market data, orders, account) and controllers depend on this type
 * rather than on any concrete broker client. To integrate a different broker,
 * provide a new implementation of this interface (and select it via the
 * {@code broker.provider} property) — no strategy, indicator, risk, or exit
 * logic needs to change.</p>
 *
 * <p>Every method returns fully neutral domain records ({@link Candle},
 * {@link Quote}, {@link AccountBalance}, {@link HoldingsResult}, {@link OrderAck},
 * {@link OrderSummary}, {@link PriceSnapshot}, {@link ConnectivityStatus}). All
 * broker-specific concerns — HTTP, authentication, JSON parsing, and the broker's
 * order-type / market-category / timespan / session vocabulary — live entirely
 * inside the adapter. A new broker is integrated by implementing this interface;
 * no strategy, indicator, risk, or exit logic changes.</p>
 *
 * <p>The current Webull implementation is
 * {@link com.trading.webull.WebullV3Client}.</p>
 */
public interface BrokerClient {

    // -----------------------------------------------------------------------
    // Accounts
    // -----------------------------------------------------------------------

    /**
     * Resolves (and caches) the account ID to trade against — either an explicit
     * configured ID or the auto-selected equity/margin account.
     *
     * @return the account ID, or {@code null} if it could not be resolved
     */
    String resolveAccountId();

    /**
     * Fetches the neutral {@link AccountBalance} (net liquidation + buying power)
     * for an account. Returns {@link AccountBalance#INVALID} on a failed call
     * (never {@code null}); the caller retains its previous snapshot in that case.
     */
    AccountBalance fetchBalance(String accountId);

    /**
     * Fetches the neutral {@link HoldingsResult} (open positions + validity flag)
     * for an account. Returns {@link HoldingsResult#INVALID} on a failed call.
     */
    HoldingsResult fetchHoldings(String accountId);

    // -----------------------------------------------------------------------
    // Market data — NEUTRAL (adapter owns broker vocabulary + JSON parsing)
    // -----------------------------------------------------------------------

    /**
     * Fetches {@code count} historical bars for one {@code symbol}, returned as
     * neutral {@link Candle}s in the order the broker returns them (typically
     * newest-first — the caller re-orders as needed).
     *
     * <p>The {@code timespan} and {@code sessions} arguments use the application's
     * canonical vocabulary (e.g. {@code M1}, {@code M30}; {@code RTH,PRE,ATH,OVN}).
     * The adapter translates these into whatever its broker requires.</p>
     *
     * @param sessions comma-separated canonical sessions, or {@code null} for the broker default
     * @throws com.trading.broker.BrokerException on a failed broker call
     */
    List<Candle> fetchBars(String symbol, int count, String timespan, String sessions);

    /**
     * Fetches bars for MANY symbols in a single request (rate-limit friendly),
     * returned as a map of symbol → neutral candles in broker order. Symbols with
     * no data are simply absent from the map.
     *
     * @throws com.trading.broker.BrokerException on a failed broker call
     */
    Map<String, List<Candle>> fetchBarsBatch(List<String> symbols, int count, String timespan, String sessions);

    /**
     * Fetches a small number of recent bars for {@code symbol} and returns them
     * newest-first, for live "latest completed bar" polling. Returns an empty list
     * on any failure (non-throwing) so the trading loop can skip a tick.
     */
    List<Candle> fetchRecentBars(String symbol, int count);

    /**
     * Fetches a real-time {@link Quote} (bid/ask/last) for {@code symbol}. Returns
     * {@link Quote#EMPTY} (all-null fields) on any failure — never {@code null}.
     */
    Quote fetchQuote(String symbol);

    /**
     * Fetches the current market/fill price for {@code symbol} — the live quote mid
     * (bid/ask) when available, else the last-trade price. Returns {@code fallback}
     * when no price can be resolved. Used to anchor brackets after a market buy and
     * to price affordability checks.
     */
    java.math.BigDecimal fetchFillPrice(String symbol, java.math.BigDecimal fallback);

    /**
     * Fetches a richer neutral {@link PriceSnapshot} (price/open/high/low/preClose/
     * volume/change/changeRatio) for {@code symbol}, for the price REST endpoint.
     * Returns {@code null} when the broker returns no snapshot for the symbol.
     *
     * @throws com.trading.broker.BrokerException on a failed broker call
     */
    PriceSnapshot fetchPriceSnapshot(String symbol);

    // -----------------------------------------------------------------------
    // Orders
    // -----------------------------------------------------------------------

    /**
     * Submits a neutral {@link OrderRequest} against an account and returns a
     * neutral {@link OrderAck}. The adapter owns all broker-specific concerns:
     * translating the request into the broker's order fields and request envelope,
     * calling the broker, and extracting the order id from the response.
     *
     * <p>Implementations must NOT retry a submission internally — a retry after a
     * request that actually succeeded (but whose response was lost) would place a
     * duplicate order.</p>
     */
    OrderAck submitOrder(String accountId, OrderRequest order);

    /**
     * Fetches historical (completed/cancelled) orders for an account as neutral
     * {@link OrderSummary} records.
     *
     * @throws com.trading.broker.BrokerException on a failed broker call
     */
    List<OrderSummary> fetchOrderHistory(String accountId, int pageSize, String paginationKey);

    /**
     * Fetches open (resting) orders for an account as neutral {@link OrderSummary}
     * records.
     *
     * @throws com.trading.broker.BrokerException on a failed broker call
     */
    List<OrderSummary> fetchOpenOrders(String accountId, int pageSize, String paginationKey);

    // -----------------------------------------------------------------------
    // Health
    // -----------------------------------------------------------------------

    /**
     * Performs a connectivity + authentication probe against the broker API and
     * returns a neutral {@link ConnectivityStatus}. Never throws — failures are
     * reported via the returned status.
     */
    ConnectivityStatus checkConnectivity();
}
