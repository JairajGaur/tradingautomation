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
             * rate limits. 1 = fully sequential. Default 3.
             */
            @DefaultValue("3") int tickerConcurrency,

            /**
             * Client-side cap on Webull bar-fetch requests per second (across all
             * threads), to avoid 429 TOO_MANY_REQUESTS. Fetches beyond this rate
             * block briefly until a slot frees. 0 = unlimited. Default 5.
             */
            @DefaultValue("5") int maxRequestsPerSecond,

            /**
             * Max allowed bid/ask spread as a FRACTION of the mid price, i.e.
             * (ask − bid) / ((ask + bid) / 2). If the spread exceeds this, no trade is
             * placed in ANY session (buy or exit). A percentage cap scales across price
             * levels (unlike a flat dollar cap): 0.003 = 0.30% of price. Also blocks
             * when bid/ask are unavailable. Set to 0 to disable the width check.
             */
            @DefaultValue("0.003") java.math.BigDecimal maxSpreadPct,

            /**
             * Position-sizing mode:
             * <ul>
             *   <li>{@code SHARES} — buy exactly {@code orderQuantity} shares (as long as
             *       buying power covers it). {@code orderBuyingPowerPct} is ignored.</li>
             *   <li>{@code PERCENT} — buy {@code floor(orderBuyingPowerPct × buyingPower / price)}
             *       shares. {@code orderQuantity} is ignored. Skips when it resolves to 0.</li>
             * </ul>
             * Default SHARES (unchanged behavior).
             */
            @DefaultValue("SHARES") String sizingMode,

            /**
             * Fraction of live buying power to deploy per trade when {@code sizingMode=PERCENT}
             * (e.g. 0.40 = 40%). Ignored in SHARES mode. Default 0.40.
             */
            @DefaultValue("0.40") java.math.BigDecimal orderBuyingPowerPct
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
            @DefaultValue("M1") String emaCrossoverTimeframe,

            /**
             * Volume filter (applies to every strategy BUY): only enter when the
             * average 1-minute volume is INCREASING. Over the last {@code volumeLookback}
             * completed bars, the recent-half average volume must exceed the older-half
             * average (e.g. last 10 vs prior 10). Default on, 20-bar lookback.
             */
            @DefaultValue("true") boolean volumeFilterEnabled,
            @DefaultValue("20")   int volumeLookback,

            /**
             * When a strategy signal fires but the volume filter isn't yet rising, hold
             * the signal for this many minutes and re-check volume each bar; buy if it
             * turns rising within the window, else drop it. 0 disables holding (a signal
             * blocked by volume is dropped immediately). Default 5.
             */
            @DefaultValue("5") int volumeHoldMinutes,

            /**
             * Cooldown: once a ticker is bought OR exited, it cannot be traded again
             * for this many minutes. Prevents immediate re-entry / churn on the same
             * name. Default 30. 0 disables.
             */
            @DefaultValue("30") int reTradeCooldownMinutes,

            /**
             * 600-EMA strategy entry — a bullish candle NEAR the 600-EMA (either side),
             * closing above it, on the latest completed bar. Captures both a bounce off
             * the EMA and an open-on-EMA momentum candle, while rejecting bars floating
             * far above the EMA (chasing highs). Checks:
             * <ul>
             *   <li>bullish: {@code close > open};</li>
             *   <li>closing above the EMA: {@code close > ema600};</li>
             *   <li>near the EMA: {@code low <= ema600 * (1 + proximityBand)} (low may be
             *       above or below the EMA — just close to it);</li>
             *   <li>optional wick rejection ({@code low < ema600}) — off by default;</li>
             *   <li>body strength: body ≥ {@code bodyMinRatio} × candle range.</li>
             * </ul>
             *
             * @param ema600ProximityBand how near the low must be to the EMA (default 0.005 = 0.5%)
             * @param ema600RequireWickRejection require {@code low < ema600} (default false)
             * @param ema600BodyMinRatio min body/range ratio, skips dojis (default 0.5)
             */
            @DefaultValue("0.005") java.math.BigDecimal ema600ProximityBand,
            @DefaultValue("false") boolean ema600RequireWickRejection,
            @DefaultValue("0.5")   java.math.BigDecimal ema600BodyMinRatio,

            /**
             * "1m Supertrend + 20/50 Cross" strategy (standalone; bypasses the universal
             * entry guard). Enters when, on {@code stCrossTimeframe}: the Supertrend is UP,
             * a FRESH 20/50 EMA golden cross prints, price is above the EMAs listed in
             * {@code stCrossAboveEmas}, and price is within {@code stCrossEma600Band} of the
             * 600-EMA (either side). Exit is owned by the ST_CROSS exit manager.
             *
             * @param stCrossEnabled    enable this strategy (default false)
             * @param stCrossTimeframe  timeframe for all its indicators (default M1)
             * @param stCrossEmaCrossFast fast EMA of the golden cross (default 20)
             * @param stCrossEmaCrossSlow slow EMA of the golden cross (default 50)
             * @param stCrossEma600Band proximity to the 600-EMA required at entry (default 0.005 = 0.5%)
             */
            @DefaultValue("false") boolean stCrossEnabled,
            @DefaultValue("M1")    String stCrossTimeframe,
            @DefaultValue("20")    int stCrossEmaCrossFast,
            @DefaultValue("50")    int stCrossEmaCrossSlow,
            @DefaultValue("0.005") java.math.BigDecimal stCrossEma600Band
    ) {}

    /**
     * Universal EXIT rule for any bot-opened position (independent of the entry
     * strategy). Implements a staged trailing stop so losers are cut at a small,
     * known risk while winners are allowed to run with the trend. The stop only ever
     * ratchets UP; the position is sold at market once price falls to/through it, or
     * when the trend fails on the EMA state.
     *
     * <p><b>Stages (each raises the effective stop):</b></p>
     * <ol>
     *   <li><b>Hard stop</b> — initial backstop at {@code entry × (1 − stopLossPct)}
     *       (default −2%). Bounds the worst-case single-trade loss.</li>
     *   <li><b>Breakeven</b> — once price reaches {@code breakevenTriggerPct} above
     *       entry (default +1%), the stop moves up to {@code entry + buffer}
     *       (buffer = current spread, at least {@code breakevenMinBufferUsd}) so the
     *       trade can no longer turn into a loss.</li>
     *   <li><b>Structure trail</b> — once price reaches {@code trailArmPct} above entry
     *       (default +1%), the stop trails the PREVIOUS completed candle's low on the
     *       exit {@code timeframe}, ratcheting up as the trend prints higher lows. This
     *       "lets the winner run" and only exits when the trend breaks structure.</li>
     * </ol>
     * <p>Independently, an <b>EMA-bearish state</b> exit fires when the {@code emaFast}
     * EMA is below the {@code emaSlow} EMA on {@code timeframe} (a state check, not just
     * the crossing bar) — a trend-failure catch.</p>
     *
     * <p>Evaluated every minute on the exit {@code timeframe}, separate from entry.</p>
     *
     * @param enabled            master on/off (default true)
     * @param stopLossPct        hard-stop fraction below entry (default 0.02 = 2%)
     * @param emaFast            fast EMA period for the bearish state exit (default 8)
     * @param emaSlow            slow EMA period for the bearish state exit (default 20)
     * @param timeframe          bar timeframe for the exit checks (default M5)
     * @param breakevenTriggerPct profit fraction that arms the breakeven stop (default 0.01 = 1%)
     * @param breakevenMinBufferUsd minimum buffer above entry for the breakeven stop ($, default 0.02)
     * @param trailArmPct        profit fraction that arms the structure trail (default 0.01 = 1%)
     * @param manager            which exit manager is active: STAGED (staged trailing stop) or
     *                           ST_CROSS (single 50-EMA trail). Exactly one runs. Default STAGED.
     * @param ema50TrailTimeframe timeframe for the ST_CROSS 50-EMA trail (default M1)
     * @param ema50TrailPct      ST_CROSS trail = ema50 × (1 − this); exit when price falls to/below
     *                           it. Ratchets up only. Default 0.0005 = 0.05% below the 50-EMA.
     */
    public record Exit(
            @DefaultValue("true")  boolean enabled,
            @DefaultValue("0.02")  java.math.BigDecimal stopLossPct,
            @DefaultValue("8")     int emaFast,
            @DefaultValue("20")    int emaSlow,
            @DefaultValue("M5")    String timeframe,
            @DefaultValue("0.01")  java.math.BigDecimal breakevenTriggerPct,
            @DefaultValue("0.02")  java.math.BigDecimal breakevenMinBufferUsd,
            @DefaultValue("0.01")  java.math.BigDecimal trailArmPct,
            @DefaultValue("STAGED") String manager,
            @DefaultValue("M1")    String ema50TrailTimeframe,
            @DefaultValue("0.0005") java.math.BigDecimal ema50TrailPct
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
     *   <li>{@code drawdownHaltEnabled} — when false, the daily drawdown halt never
     *       engages (entries are not blocked by drawdown). {@code maxDailyDrawdownPct}
     *       is ignored while disabled. Default: true.</li>
     * </ul>
     */
    public record Risk(
            @DefaultValue("0.10")  java.math.BigDecimal maxDailyDrawdownPct,
            @DefaultValue("100.0") java.math.BigDecimal minBuyingPowerUsd,
            @DefaultValue("true")  boolean accountRefreshEnabled,
            @DefaultValue("true")  boolean drawdownHaltEnabled
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
     * The guard has two legs:
     *
     * <ol>
     *   <li><b>Supertrend UP</b> on {@code supertrendTimespan} (default M30) — using
     *       {@code webull.supertrend} params.</li>
     *   <li><b>EMA stack</b> on {@code emaTimespan} (default M1): EMA{@code emaFast}
     *       above EMA{@code emaSlow}, and — when {@code emaRequireMid} is true (default) —
     *       EMA{@code emaMid} above EMA{@code emaSlow} too (default 100 &amp; 200 above 600).</li>
     * </ol>
     *
     * <p>{@code conditionLogic} controls how the two legs combine:</p>
     * <ul>
     *   <li><b>AND</b> (default) — both legs must pass. Strictest; fewest, highest-quality
     *       arms. A missing/unavailable leg blocks.</li>
     *   <li><b>OR</b> — the ticker arms once EITHER leg passes. Looser; more arms. A
     *       missing/unavailable leg simply counts as "that leg didn't pass", so the other
     *       leg can still arm the ticker.</li>
     * </ul>
     *
     * <p>All Supertrend checks use the latest <em>completed</em> bar.</p>
     *
     * @param enabled            master on/off (default true)
     * @param emaFast            fast EMA period that must exceed the slow EMA (default 100)
     * @param emaMid             mid EMA period that must exceed the slow EMA (default 200)
     * @param emaSlow            slow EMA baseline (default 600)
     * @param emaTimespan        timespan for the EMA-stack check (default M1)
     * @param supertrendTimespan timespan for the Supertrend leg (default M30)
     * @param emaRequireMid      when true, also require EMA{@code emaMid} > EMA{@code emaSlow} (default true)
     * @param conditionLogic     how the two legs combine: {@code AND} (both) or {@code OR} (either). Default AND
     */
    public record EntryGuard(
            @DefaultValue("true")  boolean enabled,
            @DefaultValue("100")   int emaFast,
            @DefaultValue("200")   int emaMid,
            @DefaultValue("600")   int emaSlow,
            @DefaultValue("M1")    String emaTimespan,
            @DefaultValue("M30")   String supertrendTimespan,
            @DefaultValue("true")  boolean emaRequireMid,
            @DefaultValue("AND")   String conditionLogic
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
