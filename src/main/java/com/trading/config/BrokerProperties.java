package com.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker-specific configuration bound from application.yml under the
 * {@code broker.webull} prefix: Webull API credentials/options and v3 endpoint paths.
 *
 * <p>Broker-neutral domain settings (engine/strategies/risk/exit/alerts/...) live in
 * {@link AppProperties} instead. Host resolution lives in the Webull adapter
 * ({@code com.trading.webull.WebullApiProperties}).</p>
 */
@ConfigurationProperties(prefix = "broker.webull")
public record BrokerProperties(
        Api api,
        Endpoints endpoints
) {

    public record Api(
            String appKey,
            String appSecret,
            @DefaultValue("us") String regionId,
            @DefaultValue("") String endpoint,

            /**
             * Optional 2FA access token (x-access-token header). Only needed when
             * Two-Factor Authentication is enabled on the account. Leave blank when
             * 2FA is off (sandbox test tokens are valid by default).
             */
            @DefaultValue("") String accessToken,

            /**
             * Optional explicit account ID to trade against. When set, this exact ID
             * is used and account-list auto-selection is skipped. Leave blank to let
             * the app auto-pick the equity (stock) account from the account list.
             */
            @DefaultValue("") String accountId,

            /**
             * When {@code true}, the outgoing request (method, path, masked headers,
             * body) and the incoming response are logged at DEBUG level. When
             * {@code false} (default), no request/response logging is emitted.
             * Note: DEBUG logging for {@code com.trading.webull} must also be enabled
             * for these lines to appear.
             */
            @DefaultValue("false") boolean logRequests
    ) {}

    /**
     * Webull OpenAPI v3 endpoint paths. Configurable so a path can be corrected
     * without a recompile if Webull changes a route. Defaults reflect the current
     * v3 API as of the migration.
     */
    public record Endpoints(
            @DefaultValue("/trading/accounts/list")          String accountList,
            @DefaultValue("/trading/assets/balances/get")    String accountBalance,
            @DefaultValue("/trading/assets/positions/list")  String positions,
            @DefaultValue("/trading/orders/place")                     String placeOrder,
            @DefaultValue("/trading/orders/historical-orders/list")    String orderHistory,
            @DefaultValue("/trading/orders/open-orders/list")          String openOrders,
            @DefaultValue("/market-data/stocks/bars/list")             String bars,
            @DefaultValue("/market-data/stocks/snapshots/list")        String snapshot,
            @DefaultValue("/market-data/options/bars/list")            String optionBars,
            @DefaultValue("/market-data/options/snapshots/list")       String optionSnapshot,
            @DefaultValue("/trading/instruments/options/contracts/list") String optionContracts
    ) {}
}
