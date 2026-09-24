package com.trading.service;

import com.trading.config.WatchlistLoader;
import com.trading.config.WebullProperties;
import com.trading.model.Candle;
import com.trading.strategy.EmaCrossoverStrategyService;
import com.trading.strategy.EmaStrategyService;
import com.trading.strategy.TradingStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Coordinates the full trading lifecycle across all tickers and all enabled strategies.
 *
 * <h2>Startup ({@link PostConstruct})</h2>
 * <ol>
 *   <li>Fetches and seeds the start-of-day equity baseline in {@link RiskManager}.</li>
 *   <li>For every ticker in the watchlist, fetches historical bars and calls
 *       {@link TradingStrategy#warmUp} on every enabled strategy.</li>
 * </ol>
 *
 * <h2>Per-minute loop ({@link Scheduled})</h2>
 * Each tick applies the following gates in order — if any gate fails the tick is
 * skipped entirely:
 * <ol>
 *   <li><b>Market hours</b> — {@link MarketHoursGuard#isTradingAllowed()}</li>
 *   <li><b>Daily drawdown halt</b> — {@link RiskManager#isTradingHalted()}</li>
 * </ol>
 * Then, for each ticker × each enabled strategy, fetches the latest candle and
 * dispatches it via {@link TradingStrategy#onCandle}.
 *
 * <h2>Daily reset</h2>
 * A separate {@code @Scheduled} method fires at 04:00 ET every day to reset the
 * drawdown tracker and re-enable trading for the new session.
 */
@Service
public class TradingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TradingOrchestrator.class);

    private final WebullProperties props;
    private final WatchlistLoader watchlistLoader;
    private final MarketHoursGuard marketHoursGuard;
    private final RiskManager riskManager;
    private final AccountService accountService;
    private final EntryGuard entryGuard;
    private final ExitManager exitManager;
    private final BarDataManager barData;
    private final PendingSignals pendingSignals;
    private final TradeCooldown tradeCooldown;
    private final com.trading.state.PositionTracker positionTracker;
    private final List<TradingStrategy> strategies;

    // Kept for instanceof enable/disable check
    private final EmaStrategyService emaStrategyService;
    private final EmaCrossoverStrategyService emaCrossoverStrategyService;

    // Bounded pool for parallel per-ticker work within a tick (null when concurrency<=1).
    private final ExecutorService tickerPool;

    public TradingOrchestrator(WebullProperties props,
                                WatchlistLoader watchlistLoader,
                                MarketHoursGuard marketHoursGuard,
                                RiskManager riskManager,
                                AccountService accountService,
                                EntryGuard entryGuard,
                                ExitManager exitManager,
                                BarDataManager barData,
                                PendingSignals pendingSignals,
                                TradeCooldown tradeCooldown,
                                com.trading.state.PositionTracker positionTracker,
                                List<TradingStrategy> strategies,
                                EmaStrategyService emaStrategyService,
                                EmaCrossoverStrategyService emaCrossoverStrategyService) {
        this.props = props;
        this.watchlistLoader = watchlistLoader;
        this.marketHoursGuard = marketHoursGuard;
        this.riskManager = riskManager;
        this.accountService = accountService;
        this.entryGuard = entryGuard;
        this.exitManager = exitManager;
        this.barData = barData;
        this.pendingSignals = pendingSignals;
        this.tradeCooldown = tradeCooldown;
        this.positionTracker = positionTracker;
        this.strategies = strategies;
        this.emaStrategyService = emaStrategyService;
        this.emaCrossoverStrategyService = emaCrossoverStrategyService;

        int concurrency = Math.max(1, props.trading().tickerConcurrency());
        this.tickerPool = concurrency > 1
                ? Executors.newFixedThreadPool(concurrency, r -> {
                    Thread t = new Thread(r, "ticker-worker");
                    t.setDaemon(true);
                    return t;
                })
                : null;   // sequential when concurrency == 1
        log.info("[Orchestrator] Ticker concurrency = {}", concurrency);
    }

    @PreDestroy
    public void shutdown() {
        if (tickerPool != null) {
            tickerPool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    @PostConstruct
    public void warmUp() {
        List<String> tickers          = watchlistLoader.getTickers();
        int warmupBars                = props.trading().warmupBars();
        List<TradingStrategy> enabled = enabledStrategies();

        log.info("[Orchestrator] === WARM-UP START === session={} tickers={} strategies={}",
                marketHoursGuard.currentSessionDescription(),
                tickers,
                enabled.stream().map(TradingStrategy::name).collect(Collectors.toList()));

        // ── Seed start-of-day equity for drawdown tracking ────────────────
        AccountService.AccountSnapshot snap = accountService.refresh();
        if (snap.netLiquidationValue().compareTo(BigDecimal.ZERO) > 0) {
            riskManager.seedStartOfDayEquity(snap.netLiquidationValue());
        } else {
            log.warn("[Orchestrator] Could not seed start-of-day equity " +
                     "(paper mode or API unavailable) — drawdown tracking inactive");
        }

        // ── Seed the position tracker from LIVE Webull holdings ───────────
        // Survives restarts: the bot knows what it already owns, so the duplicate-
        // position guard won't re-buy a ticker held from a prior run.
        for (AccountService.Holding h : accountService.listHeldPositions()) {
            if (!positionTracker.hasOpenPosition(h.symbol())) {
                positionTracker.openPosition(new com.trading.state.PositionTracker.Position(
                        h.symbol(), h.unitCost(), h.quantity(), null, null, "SEEDED_FROM_ACCOUNT"));
                log.info("[Orchestrator] Seeded existing position from account: {} x{} @ {}",
                        h.symbol(), h.quantity(), h.unitCost());
            }
        }

        // ── Initial load: warm the BarDataManager cache for every ticker across
        //    the timeframes the app uses (strategy entry timeframes, exit timeframe,
        //    and the entry guard's M30/M1). Everyone reads from this cache afterward.
        java.util.Set<String> timeframes = new java.util.LinkedHashSet<>();
        timeframes.add("M1");                                   // guard EMA stack + common
        timeframes.add("M30");                                  // guard 30m Supertrend
        timeframes.add(props.strategies().ema600Timeframe());
        timeframes.add(props.strategies().emaCrossoverTimeframe());
        timeframes.add(props.exit().timeframe());

        // One multi-symbol request per timeframe for the whole watchlist (not per ticker).
        for (String tf : timeframes) {
            log.info("[Orchestrator] Preloading {} bars for {} tickers (batch)", tf, tickers.size());
            barData.getBarsBatch(tickers, tf, warmupBars);
        }

        log.info("[Orchestrator] === WARM-UP COMPLETE ===");
    }

    // -----------------------------------------------------------------------
    // Per-minute trading loop
    // -----------------------------------------------------------------------

    @Scheduled(cron = "0 * * * * *")
    public void onMinuteTick() {

        // Nothing runs on weekends (equities don't trade).
        if (!marketHoursGuard.isTradingDay()) {
            log.debug("[Orchestrator] Weekend — skipping tick");
            return;
        }

        // ── EXITS FIRST — run on ANY trading-day minute, independent of the entry
        // trading window, so open positions are always monitored (−10% stop OR 8/20
        // bearish cross). Runs before entries so freed capital is available this tick.
        List<String> openTickers = new ArrayList<>(positionTracker.allPositions().keySet());
        if (!openTickers.isEmpty()) {
            runPerTicker(openTickers, exitManager::evaluate);
        }

        // ── ENTRIES — gated by the market-hours window (extended hours if enabled).
        if (!marketHoursGuard.isTradingAllowed()) {
            log.debug("[Orchestrator] Outside entry hours ({}) — exits only this tick",
                    marketHoursGuard.currentSessionDescription());
            return;
        }

        // Daily drawdown halt blocks new entries (exits above already ran).
        if (riskManager.isTradingHalted()) {
            log.warn("[Orchestrator] Trading halted (daily drawdown limit reached) — no entries this tick");
            return;
        }

        List<TradingStrategy> enabled = enabledStrategies();
        if (enabled.isEmpty()) {
            log.debug("[Orchestrator] No strategies enabled — skipping entries");
            return;
        }

        List<String> tickers = watchlistLoader.getTickers();

        // Batch-refresh bars for the whole watchlist BEFORE guard/strategy work, so all
        // the per-ticker getBars reads below are cache hits (1 Webull call per timeframe
        // instead of one per ticker). M1 every tick; M30 only at the :01/:31 boundary.
        int warmupBars = props.trading().warmupBars();
        barData.getBarsBatch(tickers, "M1", warmupBars);
        if (isThirtyMinuteBoundary()) {
            barData.getBarsBatch(tickers, "M30", warmupBars);
        }

        // Entry-guard arming:
        //   • On each 30-min boundary (:01/:31) run the guard for EVERY ticker.
        //   • Every other minute re-validate only ALREADY-ARMED tickers and drop
        //     any whose guard has broken. Armed tickers wait for a strategy signal.
        if (isThirtyMinuteBoundary()) {
            log.info("[Orchestrator] 30-min boundary — running entry guard for all {} ticker(s)", tickers.size());
            runPerTicker(tickers, entryGuard::refreshArmed);
        } else {
            runPerTicker(new ArrayList<>(entryGuard.armedTickers()), entryGuard::revalidateArmedTicker);
        }

        // Dispatch the tick to strategies, per ticker, in parallel.
        runPerTicker(tickers, ticker -> processTicker(ticker, enabled));

        // Re-check held signals (volume not yet rising) — buy if volume rose, else expire.
        pendingSignals.sweep();

        // ── Periodic drawdown check (even when no signal fired) ───────────
        AccountService.AccountSnapshot snap = accountService.getSnapshot();
        if (snap.netLiquidationValue().compareTo(BigDecimal.ZERO) > 0) {
            riskManager.evaluateDrawdown(snap.netLiquidationValue());
        }
    }

    /**
     * Runs {@code action} for every ticker, in parallel across the bounded pool (or
     * sequentially when concurrency == 1). Blocks until all tickers finish so the
     * tick completes before the next scheduled fire. Per-ticker failures are logged
     * and never abort the others.
     */
    private void runPerTicker(List<String> tickers, PerTicker action) {
        if (tickers.isEmpty()) return;

        if (tickerPool == null) {
            for (String ticker : tickers) {
                safeRun(ticker, action);
            }
            return;
        }

        List<Future<?>> futures = new ArrayList<>(tickers.size());
        for (String ticker : tickers) {
            futures.add(tickerPool.submit(() -> safeRun(ticker, action)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();   // wait for all; block for the tick
            } catch (Exception e) {
                log.error("[Orchestrator] Parallel ticker task failed", e);
            }
        }
    }

    @FunctionalInterface
    private interface PerTicker { void run(String ticker); }

    private void safeRun(String ticker, PerTicker action) {
        try {
            action.run(ticker);
        } catch (Exception e) {
            log.error("[Orchestrator] Per-ticker task threw for ticker={}", ticker, e);
        }
    }

    /**
     * Dispatches this tick to all enabled strategies for {@code ticker}. Strategies
     * self-fetch their own timeframe bars from {@link BarDataManager}; the candle
     * passed here is just the trigger/ticker carrier, sourced from the shared cache
     * (no extra Webull call).
     */
    private void processTicker(String ticker, List<TradingStrategy> enabled) {
        List<Candle> m1 = barData.getBars(ticker, "M1", 2);
        if (m1.isEmpty()) {
            log.debug("[Orchestrator] No M1 bars for ticker={} this tick — skipping", ticker);
            return;
        }
        Candle candle = m1.get(m1.size() - 1);
        log.debug("[Orchestrator] Dispatching tick: ticker={} close={}", ticker, candle.close());
        for (TradingStrategy strategy : enabled) {
            try {
                strategy.onCandle(candle);
            } catch (Exception e) {
                log.error("[Orchestrator] Strategy '{}' threw on candle for ticker={}",
                        strategy.name(), ticker, e);
                // Continue — one failure must not abort the others
            }
        }
    }

    /**
     * True one minute after each 30-minute boundary (minute :01 or :31). Running the
     * full-watchlist guard sweep at :01/:31 rather than exactly :00/:30 ensures the
     * just-closed 30-minute Supertrend bar has settled and is queryable from Webull.
     * The app runs in ET and the scheduler fires at second 0 of every minute.
     */
    private boolean isThirtyMinuteBoundary() {
        return java.time.LocalTime.now().getMinute() % 30 == 1;
    }

    // -----------------------------------------------------------------------
    // Daily reset — fires at 04:00 ET every weekday
    // -----------------------------------------------------------------------

    /**
     * Resets the daily drawdown tracker at the start of each new trading day.
     * 04:00 ET is chosen so it fires before pre-market opens (configurable to 04:00).
     */
    @Scheduled(cron = "0 0 4 * * MON-FRI", zone = "America/New_York")
    public void onDailyReset() {
        log.info("[Orchestrator] === DAILY RESET (04:00 ET) ===");
        riskManager.resetForNewDay();
        entryGuard.clearAll();   // no armed state carries over to the new day
        pendingSignals.clearAll();   // drop any held signals
        tradeCooldown.clearAll();    // reset per-ticker cooldowns
        barData.clear();         // drop cached bars; re-fetched fresh for the new day

        // Re-seed start-of-day equity for the new session
        AccountService.AccountSnapshot snap = accountService.refresh();
        if (snap.netLiquidationValue().compareTo(BigDecimal.ZERO) > 0) {
            riskManager.seedStartOfDayEquity(snap.netLiquidationValue());
            log.info("[Orchestrator] New day equity baseline: {}", snap.netLiquidationValue());
        }
    }

    // -----------------------------------------------------------------------
    // Strategy enable / disable
    // -----------------------------------------------------------------------

    private List<TradingStrategy> enabledStrategies() {
        return strategies.stream()
                .filter(this::isEnabled)
                .collect(Collectors.toList());
    }

    private boolean isEnabled(TradingStrategy strategy) {
        if (strategy instanceof EmaStrategyService) {
            return props.strategies().ema600Enabled();
        }
        if (strategy instanceof EmaCrossoverStrategyService) {
            return props.strategies().emaCrossoverEnabled();
        }
        log.warn("[Orchestrator] No enable flag for strategy '{}' — treating as enabled",
                strategy.name());
        return true;
    }
}
