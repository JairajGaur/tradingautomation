package com.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed, broker-NEUTRAL domain configuration bound from application.yml under the
 * {@code app} prefix. Holds the trading engine, strategies, exit/risk rules,
 * market hours, indicator params, entry guard, notifications and alerts.
 *
 * <p>Broker-specific settings (API credentials, endpoint paths, host) live in
 * {@link BrokerProperties} instead.</p>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Engine engine,
        Strategies strategies,
        Exit exit,
        Risk risk,
        MarketHours marketHours,
        Supertrend supertrend,
        @DefaultValue Adx adx,
        EntryGuard entryGuard,
        @DefaultValue Notifications notifications,
        @DefaultValue Alerts alerts,
        @DefaultValue Llm llm,
        @DefaultValue AlertReview alertReview
) {

    public enum Mode { PAPER, LIVE }

    /**
     * Push-notification transport config. When {@code enabled} and {@code channel=TELEGRAM},
     * messages are delivered to a Telegram chat/channel via the Bot API. This is just the
     * delivery channel — WHAT gets sent is driven by the alert framework ({@code app.alerts})
     * and the {@code /api/notifications/test} endpoint. Failures never affect trading.
     *
     * @param enabled          master on/off (default false)
     * @param channel          delivery channel: TELEGRAM (only one for now); NONE disables
     * @param telegramBotToken bot token from @BotFather
     * @param telegramChatId   target chat id (your user id, or a group/channel id like @name or -100...)
     */
    public record Notifications(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("TELEGRAM") String channel,
            @DefaultValue("") String telegramBotToken,
            @DefaultValue("") String telegramChatId,
            /** Master toggle for native macOS desktop (banner) notifications on EVERY fired
             *  alert, in ADDITION to Telegram. Default false. No-op on non-mac. */
            @DefaultValue("false") boolean desktopEnabled,
            /** macOS notification sound name (e.g. Ping, Glass, Hero). Blank = silent. */
            @DefaultValue("Ping") String desktopSound
    ) {}

    /**
     * Pluggable market-alert framework. One scheduler ({@code AlertEngine}) runs every
     * {@code scanMinutes} minutes, evaluating each enabled {@code Alert} over the universe
     * using the shared {@code BarDataManager} (no separate data fetching), and posts hits
     * to the notification channel. Every message is prefixed with {@code messagePrefix}.
     *
     * @param enabled        master on/off for all alerts (default false)
     * @param scanMinutes    scan interval in minutes (default 5)
     * @param messagePrefix  text prepended to every alert message (default "")
     * @param stAdx          the "M5 Supertrend + ADX" alert config
     */
    public record Alerts(
            @DefaultValue("false") boolean enabled,
            @DefaultValue StAdxAlertCfg stAdx,
            @DefaultValue Orb orb,
            @DefaultValue SwiftScanCfg swiftScan
    ) {
        /**
         * All Opening-Range-Breakout config under one parent ({@code alerts.orb}).
         *
         * @param rangeMinutes     opening-range length in minutes → the Webull timeframe
         *                         whose 09:30 ET candle IS the range (15 → M15 = 09:30–09:45,
         *                         30 → M30 = 09:30–10:00). Webull timeframe: 5/15/30/60.
         *                         Fetched once/day and cached (late-start safe).
         * @param emaPeriod        EMA period for the slope confirmation (default 600).
         * @param emaSlopeLookback candles back to measure the EMA slope; UP needs
         *                         EMA(now) ≥ EMA(lookback ago), DOWN ≤ (default 3).
         * @param stSlopeConfirmEnabled when true, add a Supertrend-line-slope confluence gate:
         *                         an UP break fires only if the Supertrend LINE has been
         *                         advancing UP (DOWN mirrors). Uses the shared
         *                         {@code app.supertrend} length/factor. Default false (no gate).
         * @param stSlopeTimeframe  timeframe for the Supertrend used by this gate — INDEPENDENT of
         *                         the ORB range/scan timeframes (any M1/M5/… or synthetic M2/M3).
         *                         Default M5.
         * @param stSlopeLookback  span, in bars, over which ONE slope measurement is taken — the
         *                         Supertrend line's advance in the trend direction (same meaning
         *                         as the Swift scanner's st-slope-lookback). Default 3.
         * @param stSlopeMinAtr    minimum line advance over {@code stSlopeLookback} bars, in ATR
         *                         units, for a bar to count as "advancing in-direction" (same as
         *                         the Swift scanner's min-st-slope-atr). Default 0.1.
         * @param stSlopeBars      the "N": the gate passes if ANY of the last N completed bars has
         *                         a slope meeting {@code stSlopeMinAtr} in the break direction (so
         *                         a currently-flat line still passes if it moved in-direction on
         *                         any recent bar). Default 3.
         * @param up / down        per-direction enable, scan-minutes timeframe, prefix.
         */
        public record Orb(
                @DefaultValue("15")   int rangeMinutes,
                @DefaultValue("600")  int emaPeriod,
                @DefaultValue("3")    int emaSlopeLookback,
                /** When true (default), require the EMA slope to confirm the break
                 *  (rising for UP, falling for DOWN). false = pure break, no EMA gate. */
                @DefaultValue("true") boolean emaSlopeEnabled,
                @DefaultValue("false") boolean stSlopeConfirmEnabled,
                @DefaultValue("M5")   String stSlopeTimeframe,
                @DefaultValue("3")    int stSlopeLookback,
                @DefaultValue("0.1")  java.math.BigDecimal stSlopeMinAtr,
                @DefaultValue("3")    int stSlopeBars,
                /** Default Telegram channel for BOTH ORB directions; a direction's own
                 *  {@code telegram-chat-id} overrides it. Blank → that direction has no channel
                 *  and is skipped (no common fallback). */
                @DefaultValue("")     String telegramChatId,
                @DefaultValue OrbAlertCfg up,
                @DefaultValue OrbAlertCfg down
        ) {}

        /**
         * "Supertrend + ADX agree" alert: on {@code timeframe} (default M5), flag a ticker
         * BULLISH when Supertrend is UP and the ADX recommendation is BUY, or BEARISH when
         * Supertrend is DOWN and ADX recommends SELL. Each alert runs on its OWN
         * {@code scanMinutes} cadence and has its own {@code messagePrefix}.
         */
        public record StAdxAlertCfg(
                @DefaultValue("true") boolean enabled,
                /**
                 * Comma-separated Supertrend timeframes that must ALL agree in the alert
                 * direction (e.g. "M5" or "M5,M15"). The FIRST timeframe is the primary —
                 * the ADX recommendation and the message price are taken from it. BULLISH =
                 * Supertrend UP on every listed timeframe AND ADX BUY on the primary;
                 * BEARISH = Supertrend DOWN on every listed timeframe AND ADX SELL.
                 */
                @DefaultValue("M5")   String supertrendTimeframes,
                @DefaultValue("5")    int scanMinutes,
                @DefaultValue("")     String messagePrefix,
                /**
                 * Optional price-vs-EMA gate. When {@code emaEnabled}, a BULLISH alert also
                 * requires the latest close on {@code emaTimeframe} to be ABOVE EMA(emaPeriod),
                 * and BEARISH requires it BELOW. Independent of the Supertrend timeframes
                 * (e.g. ST on M5,M15 but this gate on 600-EMA / M1). Default off.
                 */
                @DefaultValue("false") boolean emaEnabled,
                @DefaultValue("600")   int emaPeriod,
                @DefaultValue("M1")    String emaTimeframe,
                /** Telegram channel for ST+ADX alerts. Blank → no channel; the alert is skipped
                 *  by the engine (no common fallback). */
                @DefaultValue("")      String telegramChatId
        ) {}

        /**
         * Opening-Range-Breakout alert config (independent for UP and DOWN). The break is
         * a CLOSE on the {@code scanMinutes} timeframe beyond the opening range (from
         * {@code orbRangeMinutes}), and the alert also polls on that timeframe. So
         * {@code scanMinutes} both selects the breakout candle timeframe AND the poll
         * cadence — it MUST be a Webull-supported timeframe (1/5/15/30/60). An EMA slope
         * confirmation on the same timeframe is also required. Once per ticker per day.
         * Each direction has its own enable flag, timeframe, prefix, and optional Telegram
         * channel. When {@code telegramChatId} is blank this direction falls back to the
         * ORB-parent {@code orb.telegram-chat-id}; there is NO global/common channel.
         */
        public record OrbAlertCfg(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("5")     int scanMinutes,
                @DefaultValue("")      String messagePrefix,
                @DefaultValue("")      String telegramChatId
        ) {}

        /**
         * "Swift Trend Scanner" — scans the universe for a <b>fresh Supertrend direction
         * flip backed by strong, ATR-normalised momentum</b> (pure Supertrend + momentum,
         * no EMA). A swift UP = a recent flip to UP that immediately runs; swift DOWN is
         * the mirror. Slow grinds that hug the line, choppy whipsaws, and exhausted/
         * extended moves are all filtered out. Fires once per flip per symbol (then a
         * {@code cooldownMinutes} cooldown) and posts each signal as its OWN message to a
         * SEPARATELY-configurable Telegram channel ({@code telegramChatId}), reusing the
         * shared bot token. Runs via the shared {@code AlertEngine} tick (so it inherits
         * market-hours gating), on its own {@code scanMinutes} cadence.
         *
         * <p>All momentum thresholds are ATR-relative so one set of numbers works across
         * differently-priced symbols. See {@code SwiftTrendDetector.Thresholds}.</p>
         *
         * @param enabled           enable the scanner (default false)
         * @param timeframe         Webull timeframe for the Supertrend/candles (default M5)
         * @param scanMinutes       how often the scan runs, in minutes (default 1)
         * @param messagePrefix     text prepended to the message title (default "SWIFT TREND - ")
         * @param cooldownMinutes   per-symbol+direction suppression after a signal (default 30)
         * @param telegramChatId    the SEPARATE Telegram channel/chat id for swift alerts.
         *                          @name (public channel), -100... (private), or a user id.
         *                          Blank → falls back to the main notifications chat id.
         * @param minTravelAtr      min price travel since the flip, in ATR units (default 2.5)
         * @param minSeparationAtr  min current |close−supertrend|/ATR, must widen (default 1.5)
         * @param strongCandleWindow window of recent candles inspected for strength (default 5)
         * @param minStrongCandles  how many of that window must be strong bodies (default 3)
         * @param bodyMinAtr        min candle body as a fraction of ATR to count strong (default 0.5)
         * @param flipWindowBars    window used by the whipsaw guard (default 5)
         * @param maxFlipsInWindow  reject when flips within the window reach this count (default 2)
         * @param stSlopeLookback   bars back over which the Supertrend LINE's own movement is
         *                          measured (default 3)
         * @param minStSlopeAtr     min Supertrend-line advance in the trend direction over
         *                          {@code stSlopeLookback} bars, in ATR units (default 0.1).
         *                          Rejects a flat/shelved or wrong-way line.
         * @param maxCandleAtr      max allowed range (high−low) of the signal bar, in ATR
         *                          units (default 3.0). Rejects climax/blow-off spike bars.
         *                          0 or less disables the cap.
         */
        public record SwiftScanCfg(
                @DefaultValue("false")  boolean enabled,
                @DefaultValue("M5")     String timeframe,
                @DefaultValue("1")      int scanMinutes,
                @DefaultValue("SWIFT TREND - ") String messagePrefix,
                @DefaultValue("30")     int cooldownMinutes,
                @DefaultValue("")       String telegramChatId,
                @DefaultValue("2.5")    java.math.BigDecimal minTravelAtr,
                @DefaultValue("1.5")    java.math.BigDecimal minSeparationAtr,
                @DefaultValue("5")      int strongCandleWindow,
                @DefaultValue("3")      int minStrongCandles,
                @DefaultValue("0.5")    java.math.BigDecimal bodyMinAtr,
                @DefaultValue("5")      int flipWindowBars,
                @DefaultValue("2")      int maxFlipsInWindow,
                @DefaultValue("3")      int stSlopeLookback,
                @DefaultValue("0.1")    java.math.BigDecimal minStSlopeAtr,
                @DefaultValue("3.0")    java.math.BigDecimal maxCandleAtr
        ) {}
    }

    public record Engine(
            @DefaultValue("PAPER") Mode mode,

            /**
             * Master switch for the AUTOMATED trading loop. When {@code false}, the
             * scheduled per-minute tick does nothing — no strategy entries and no
             * automatic exits (full stop). REST endpoints are unaffected, and MANUAL
             * trades via the manual-trade endpoint still work (the override). Default true.
             */
            @DefaultValue("true") boolean tradingEnabled,

            @DefaultValue("AAPL") String ticker,
            @DefaultValue("classpath:tickers.txt") String watchlistPath,

            /**
             * Pool of candidate symbols screened by the Supertrend filter endpoint
             * (GET /api/filter/supertrend) when no explicit {@code ?tickers=} list is
             * passed. NOT the trading watchlist. Any Spring resource path.
             */
            @DefaultValue("classpath:universe.txt") String universePath,

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
             */
            @DefaultValue("0.005") java.math.BigDecimal ema600ProximityBand,
            @DefaultValue("false") boolean ema600RequireWickRejection,

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
            @DefaultValue("0.005") java.math.BigDecimal stCrossEma600Band,

            /**
             * How the 20/50 relationship qualifies an entry:
             * <ul>
             *   <li>{@code false} (default) — <b>state</b>: enter whenever EMA(fast) &gt; EMA(slow)
             *       (trend already up), not just the crossover bar. Catches trends in progress.</li>
             *   <li>{@code true} — <b>fresh cross</b>: enter only on the exact bar where EMA(fast)
             *       crosses above EMA(slow). Misses trends already underway.</li>
             * </ul>
             * The one-position, cooldown, ST-UP and strong-body gates prevent churn either way.
             */
            @DefaultValue("false") boolean stCrossRequireFreshCross,

            /**
             * Shared "strong green candle" body/range ratio used by ALL strategies (single
             * source of truth via {@code CandleStrength}). A signal bar must be green and
             * its body ≥ this fraction of its high-low range. Default 0.5 (body ≥ 50%).
             */
            @DefaultValue("0.5")   java.math.BigDecimal bodyMinRatio,

            /**
             * Shared "don't buy the high" filter used by ALL strategies. When enabled, a BUY
             * is blocked if price is extended too far above the reference EMA — i.e.
             * {@code price > ema(noBuyHighRefEma) × (1 + noBuyHighMaxExtension)}. Prevents
             * chasing a move that has already run far from its trend baseline.
             *
             * @param noBuyHighEnabled       master on/off (default false — no restriction)
             * @param noBuyHighRefEma        reference EMA period (default 200)
             * @param noBuyHighMaxExtension  max fraction above that EMA (default 0.02 = 2%)
             */
            @DefaultValue("false") boolean noBuyHighEnabled,
            @DefaultValue("200")   int noBuyHighRefEma,
            @DefaultValue("0.01")  java.math.BigDecimal noBuyHighMaxExtension,

            /**
             * Shared "never buy below an EMA floor" filter used by ALL strategies. When
             * enabled, a BUY is blocked if price is at/below the {@code noBuyBelowRefEma}
             * EMA. Default: block below the 50-EMA.
             *
             * @param noBuyBelowEnabled master on/off (default false)
             * @param noBuyBelowRefEma  reference EMA floor period (default 50)
             */
            @DefaultValue("false") boolean noBuyBelowEnabled,
            @DefaultValue("50")    int noBuyBelowRefEma
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
     * ADX / Directional-Movement parameters — a property of the indicator itself,
     * used anywhere the app computes its canonical ADX (via {@code AdxService}).
     *
     * @param period    DI/ADX lookback (Wilder default 14)
     * @param threshold ADX level separating weak/ranging from trending (default 25).
     *                  At/above this ADX reads as a real trend; below it is weak/ranging.
     * @param rising    bars back ADX is compared against to judge "rising" (default 3),
     *                  used by the LOW_ADX_RISING pattern flag.
     */
    public record Adx(
            @DefaultValue("14") int period,
            @DefaultValue("25") java.math.BigDecimal threshold,
            @DefaultValue("3")  int rising
    ) {}

    /**
     * Universal entry guard applied to every BUY (long/call) across all strategies.
     * The guard has two legs:
     *
     * <ol>
     *   <li><b>Supertrend UP</b> on {@code supertrendTimespan} (default M30) — using
     *       {@code app.supertrend} params.</li>
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
     * Local-LLM "analyst" layer config (broker-NEUTRAL — a domain capability, not a
     * broker setting, so it lives here under {@code app.llm} rather than in
     * {@link BrokerProperties}).
     *
     * <p>The LLM is an <b>advisory reasoning layer</b> on top of the deterministic
     * indicators/alerts. It never computes indicator math and never places orders —
     * it turns a structured signal briefing into a conviction score + action
     * recommendation + rationale, which the risk/exit layer may then act on (and can
     * always override). If the LLM is disabled, unreachable, slow, or returns garbage,
     * callers fail safe to a neutral {@code WAIT} verdict — trading is never broken by
     * an LLM hiccup (same spirit as notifications: failures never affect trading).</p>
     *
     * <p>Talks to any OpenAI-/Ollama-style local runtime over HTTP. The default targets
     * a local <a href="https://ollama.com">Ollama</a> server. Swapping runtimes (e.g.
     * llama.cpp's server) is just a {@code base-url}/{@code model} change — no code.</p>
     *
     * @param enabled        master on/off (default false — opt in explicitly)
     * @param baseUrl        runtime base URL (default local Ollama {@code http://localhost:11434})
     * @param model          model tag to run (default {@code qwen3:8b})
     * @param generatePath   generate endpoint path appended to {@code baseUrl} (default {@code /api/generate})
     * @param temperature    sampling temperature; low = deterministic/disciplined (default 0.2)
     * @param think          enable the model's step-by-step reasoning monologue. false =
     *                       fast, clean answer (good for per-alert scoring); true = slower,
     *                       deeper (good for end-of-day review). Default false.
     * @param timeoutSeconds per-call HTTP timeout; on timeout the caller fails safe (default 20)
     * @param maxConviction  conviction scale upper bound the model is told to use (default 10)
     */
    public record Llm(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen3:8b") String model,
            @DefaultValue("/api/generate") String generatePath,
            @DefaultValue("0.2") java.math.BigDecimal temperature,
            @DefaultValue("false") boolean think,
            @DefaultValue("20") int timeoutSeconds,
            @DefaultValue("10") int maxConviction
    ) {
        /** Full generate URL ({@code baseUrl} + {@code generatePath}), trimming any dup slash. */
        public String generateUrl() {
            String b = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
            String p = generatePath == null ? "" : generatePath;
            if (!p.startsWith("/")) p = "/" + p;
            return b + p;
        }
    }

    /**
     * Parallel LLM "alert review" pipeline (broker-NEUTRAL). When an alert fires, a separate
     * ASYNC pipeline gathers a DAY-TRADING multi-timeframe snapshot of the symbol and asks the
     * LLM to judge whether the evidence AGREES with the alert's direction, with a conviction
     * score and a one-line reason. The result is logged (structured line + JSON sidecar) for
     * end-of-day analysis.
     *
     * <p><b>Off the critical path.</b> Runs on its own executor AFTER the alert is delivered
     * and logged, so it never slows or blocks alert delivery. Any failure is swallowed
     * (fail-safe) — the alert pipeline is unaffected.</p>
     *
     * <p><b>Day-trading timeframes.</b> M15 / M5 (primary) / M1 — with bar counts large enough
     * that Supertrend and ADX actually compute on each (ADX needs ~2×period+1 bars). A compact
     * daily-bias line (prior close vs today's open) gives higher-timeframe context without a
     * near-useless 2-bar daily indicator block. Each timeframe is DISTILLED to one line
     * (Supertrend + ADX + volume trend + a fast EMA stack + position in the day's range) —
     * never raw bars — so the whole briefing stays small.</p>
     *
     * @param enabled      master on/off (default false — opt in explicitly)
     * @param model        model tag for the review; blank → {@code app.llm.model}. Defaults to
     *                     a deeper model ({@code qwen3:14b}) since latency is off the critical path.
     * @param m15Bars      M15 bars to fetch (default 80 — swing/structure context)
     * @param m5Bars       M5 bars to fetch (default 120 — the primary day-trade timeframe)
     * @param m1Bars       M1 bars to fetch (default 200 — entry timing / today's session)
     * @param emaPeriods   fast EMA periods per timeframe; blank → defaults to day-trade set
     *                     {@code 9, 20, 50}
     * @param poolSize     async worker threads for reviews (default 2)
     * @param logJson      also write a per-review JSON sidecar (default true)
     * @param jsonDir      directory for the JSON sidecars (default {@code logs/alert-reviews})
     */
    public record AlertReview(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("qwen3:14b") String model,
            @DefaultValue("80")  int m15Bars,
            @DefaultValue("120") int m5Bars,
            @DefaultValue("200") int m1Bars,
            @DefaultValue({"9", "20", "50"}) java.util.List<Integer> emaPeriods,
            @DefaultValue("2")   int poolSize,
            @DefaultValue("true") boolean logJson,
            @DefaultValue("logs/alert-reviews") String jsonDir
    ) {}

    // -----------------------------------------------------------------------
    // Derived helpers
    // -----------------------------------------------------------------------

    /** Returns {@code true} when running in LIVE (production) mode. */
    public boolean isLiveMode() {
        return engine().mode() == Mode.LIVE;
    }

    /**
     * Returns {@code true} when orders should be sent to the broker API.
     *
     * <p>Controlled by the single {@code engine.mode} switch: orders are always
     * submitted to the resolved endpoint — the SANDBOX in {@code PAPER}
     * mode (no real money) and PRODUCTION in {@code LIVE} mode (real money).</p>
     */
    public boolean shouldSubmitOrders() {
        return true;
    }
}
