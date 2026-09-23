package com.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration properties bound from application.yml under the
 * {@code webull} prefix.
 */
@ConfigurationProperties(prefix = "webull")
public record WebullProperties(
        Api api,
        Trading trading,
        Strategies strategies,
        Exit exit,
        Risk risk,
        MarketHours marketHours,
        Supertrend supertrend,
        EntryGuard entryGuard,
        Endpoints endpoints
) {

    public enum Mode { PAPER, LIVE }

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

    public record Trading(
            @DefaultValue("PAPER") Mode mode,
            @DefaultValue("AAPL") String ticker,
            @DefaultValue("classpath:tickers.txt") String watchlistPath,
            @DefaultValue("700") int warmupBars,
            @DefaultValue("600") int emaPeriod,
            @DefaultValue("1") int orderQuantity,

            /**
             * EMA periods exposed by the /api/ema endpoint. Configurable list —
             * defaults to the periods used across the strategies plus common ones.
             */
            @DefaultValue({"20", "100", "200", "600"}) java.util.List<Integer> emaPeriods,

            /**
             * Default trading sessions included in bar/market-data requests.
             * Comma-separated subset of: RTH (regular 9:30–16:00), PRE (pre-market),
             * ATH (after-hours), OVN (overnight). Multiple sessions are supported.
             * Per-request {@code sessions} query params on the EMA/History endpoints
             * override this default.
             */
            @DefaultValue("RTH,PRE,ATH") String tradingSessions,

            /**
             * Max number of tickers processed in parallel per trading tick (guard
             * sweep + candle dispatch). Bounds concurrent Webull calls to respect
             * rate limits. 1 = fully sequential. Default 8.
             */
            @DefaultValue("8") int tickerConcurrency
    ) {}

    public record Strategies(
            @DefaultValue("true") boolean ema600Enabled,
            @DefaultValue("true") boolean emaCrossoverEnabled,

            /**
             * Bar timeframe each strategy evaluates its ENTRY signal on. Must be a
             * Webull-supported timespan (M1, M5, M15, M30, M60, ...). Default M1.
             * Strategies are entry-only; exits are handled by the universal exit rule.
             */
            @DefaultValue("M1") String ema600Timeframe,
            @DefaultValue("M1") String emaCrossoverTimeframe
    ) {}

    /**
     * Universal EXIT rule for any bot-opened position (independent of the entry
     * strategy). A position is sold at market when EITHER condition is met:
     * <ol>
     *   <li>price ≤ entry × (1 − {@code stopLossPct}) — default −10%; or</li>
     *   <li>a fresh bearish EMA cross on {@code timeframe}: EMA{@code emaFast} was ≥
     *       EMA{@code emaSlow} on the prior completed bar and is now below it
     *       (default 8-EMA crossing under 20-EMA).</li>
     * </ol>
     * Evaluated every minute on the exit {@code timeframe}, separate from entry.
     *
     * @param enabled     master on/off (default true)
     * @param stopLossPct stop-loss fraction below entry (default 0.10 = 10%)
     * @param emaFast     fast EMA period for the bearish cross (default 8)
     * @param emaSlow     slow EMA period for the bearish cross (default 20)
     * @param timeframe   bar timeframe for the exit checks (default M1)
     */
    public record Exit(
            @DefaultValue("true")  boolean enabled,
            @DefaultValue("0.10")  java.math.BigDecimal stopLossPct,
            @DefaultValue("8")     int emaFast,
            @DefaultValue("20")    int emaSlow,
            @DefaultValue("M1")    String timeframe
    ) {}

    /**
     * Risk management configuration.
     *
     * <ul>
     *   <li>{@code maxDailyDrawdownPct} — halt all trading for the rest of the day when
     *       unrealised + realised losses exceed this fraction of start-of-day equity.
     *       Default: 0.10 (10 %).</li>
     *   <li>{@code minBuyingPowerUsd} — refuse any new BUY order when available buying
     *       power (cash) falls below this dollar amount.  Default: $100.</li>
     *   <li>{@code accountRefreshEnabled} — fetch a fresh account snapshot after every
     *       order.  Disable to reduce API traffic in paper mode.  Default: true.</li>
     * </ul>
     */
    public record Risk(
            @DefaultValue("0.10")  java.math.BigDecimal maxDailyDrawdownPct,
            @DefaultValue("100.0") java.math.BigDecimal minBuyingPowerUsd,
            @DefaultValue("true")  boolean accountRefreshEnabled
    ) {}

    /**
     * Market-hours configuration (all times in America/New_York).
     *
     * <ul>
     *   <li>{@code coreOpen}  / {@code coreClose} — regular session (09:30–16:00).</li>
     *   <li>{@code extendedHoursEnabled} — when true, also trade during pre-market and
     *       after-hours windows defined below.</li>
     *   <li>{@code preMarketOpen}    — pre-market session start (default 04:00).</li>
     *   <li>{@code afterHoursClose}  — after-hours session end   (default 20:00).</li>
     * </ul>
     */
    public record MarketHours(
            @DefaultValue("09:30") String coreOpen,
            @DefaultValue("16:00") String coreClose,
            @DefaultValue("false") boolean extendedHoursEnabled,
            @DefaultValue("04:00") String preMarketOpen,
            @DefaultValue("20:00") String afterHoursClose
    ) {}

    /**
     * Supertrend indicator parameters — a property of the indicator itself, used
     * anywhere the app computes its canonical Supertrend (via {@code SupertrendService}).
     *
     * @param length ATR period (default 7)
     * @param factor ATR multiplier (default 3)
     */
    public record Supertrend(
            @DefaultValue("7") int length,
            @DefaultValue("3") java.math.BigDecimal factor
    ) {}

    /**
     * Universal entry guard applied to every BUY (long/call) across all strategies.
     * A BUY is only allowed when ALL checks pass. They are evaluated fail-fast in
     * this fixed order (slowest/cached first, fastest last):
     *
     * <ol>
     *   <li><b>30m Supertrend UP</b> — using {@code webull.supertrend} params.</li>
     *   <li><b>EMA stack</b> on 1-minute bars: EMA{@code emaFast} and EMA{@code emaMid}
     *       are both above EMA{@code emaSlow} (default 100 &amp; 200 above 600).</li>
     * </ol>
     *
     * <p>All Supertrend checks use the latest <em>completed</em> bar. The first
     * failing check short-circuits and blocks the buy.</p>
     *
     * @param enabled      master on/off (default true)
     * @param emaFast      fast EMA period that must exceed the slow EMA (default 100)
     * @param emaMid       mid EMA period that must exceed the slow EMA (default 200)
     * @param emaSlow      slow EMA baseline (default 600)
     * @param emaTimespan  timespan for the EMA-stack check (default M1)
     */
    public record EntryGuard(
            @DefaultValue("true")  boolean enabled,
            @DefaultValue("100")   int emaFast,
            @DefaultValue("200")   int emaMid,
            @DefaultValue("600")   int emaSlow,
            @DefaultValue("M1")    String emaTimespan
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
            @DefaultValue("/market-data/stocks/snapshots/list")        String snapshot
    ) {}

    // -----------------------------------------------------------------------
    // Derived helpers
    // -----------------------------------------------------------------------

    public String resolvedApiHost() {
        if (api.endpoint() != null && !api.endpoint().isBlank()) {
            return api.endpoint();
        }
        return trading.mode() == Mode.LIVE
                ? "api.webull.com"
                : "api.sandbox.webull.com";
    }

    /** Returns {@code true} when running in LIVE (production) mode. */
    public boolean isLiveMode() {
        return trading.mode() == Mode.LIVE;
    }

    /**
     * Returns {@code true} when orders should be sent to the Webull API.
     *
     * <p>Controlled by the single {@code trading.mode} switch: orders are always
     * submitted to the resolved endpoint — the Webull SANDBOX in {@code PAPER}
     * mode (no real money) and PRODUCTION in {@code LIVE} mode (real money).</p>
     */
    public boolean shouldSubmitOrders() {
        return true;
    }
}
