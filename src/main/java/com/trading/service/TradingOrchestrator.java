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

import java.math.BigDecimal;
import java.util.List;
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
    private final MarketDataService marketDataService;
    private final MarketHoursGuard marketHoursGuard;
    private final RiskManager riskManager;
    private final AccountService accountService;
    private final List<TradingStrategy> strategies;

    // Kept for instanceof enable/disable check
    private final EmaStrategyService emaStrategyService;
    private final EmaCrossoverStrategyService emaCrossoverStrategyService;

    public TradingOrchestrator(WebullProperties props,
                                WatchlistLoader watchlistLoader,
                                MarketDataService marketDataService,
                                MarketHoursGuard marketHoursGuard,
                                RiskManager riskManager,
                                AccountService accountService,
                                List<TradingStrategy> strategies,
                                EmaStrategyService emaStrategyService,
                                EmaCrossoverStrategyService emaCrossoverStrategyService) {
        this.props = props;
        this.watchlistLoader = watchlistLoader;
        this.marketDataService = marketDataService;
        this.marketHoursGuard = marketHoursGuard;
        this.riskManager = riskManager;
        this.accountService = accountService;
        this.strategies = strategies;
        this.emaStrategyService = emaStrategyService;
        this.emaCrossoverStrategyService = emaCrossoverStrategyService;
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

        // ── EMA warm-up per ticker ────────────────────────────────────────
        for (String ticker : tickers) {
            log.info("[Orchestrator] Warming up ticker={}", ticker);

            List<Candle> history = marketDataService.fetchHistoricalBarsQuietly(ticker, warmupBars);
            if (history.isEmpty()) {
                log.warn("[Orchestrator] No historical data for ticker={} — skipping", ticker);
                continue;
            }

            List<BigDecimal> closes = history.stream()
                    .map(Candle::close)
                    .collect(Collectors.toList());

            for (TradingStrategy strategy : enabled) {
                try {
                    strategy.warmUp(ticker, closes);
                } catch (Exception e) {
                    log.error("[Orchestrator] Warm-up failed for strategy='{}' ticker={}",
                            strategy.name(), ticker, e);
                }
            }
        }

        log.info("[Orchestrator] === WARM-UP COMPLETE ===");
    }

    // -----------------------------------------------------------------------
    // Per-minute trading loop
    // -----------------------------------------------------------------------

    @Scheduled(cron = "0 * * * * *")
    public void onMinuteTick() {

        // ── Gate 1: market hours ──────────────────────────────────────────
        if (!marketHoursGuard.isTradingAllowed()) {
            log.debug("[Orchestrator] Outside trading hours ({}) — skipping tick",
                    marketHoursGuard.currentSessionDescription());
            return;
        }

        // ── Gate 2: daily drawdown halt ───────────────────────────────────
        if (riskManager.isTradingHalted()) {
            log.warn("[Orchestrator] Trading halted (daily drawdown limit reached) — skipping tick");
            return;
        }

        List<TradingStrategy> enabled = enabledStrategies();
        if (enabled.isEmpty()) {
            log.debug("[Orchestrator] No strategies enabled — skipping tick");
            return;
        }

        List<String> tickers = watchlistLoader.getTickers();

        for (String ticker : tickers) {
            Candle candle = marketDataService.fetchLatestBar(ticker);
            if (candle == null) {
                log.debug("[Orchestrator] No candle for ticker={} this tick — skipping", ticker);
                continue;
            }

            log.debug("[Orchestrator] Dispatching candle: ticker={} open={} close={}",
                    ticker, candle.open(), candle.close());

            for (TradingStrategy strategy : enabled) {
                try {
                    strategy.onCandle(candle);
                } catch (Exception e) {
                    log.error("[Orchestrator] Strategy '{}' threw on candle for ticker={}",
                            strategy.name(), ticker, e);
                    // Continue — one failure must not abort the loop
                }
            }
        }

        // ── Periodic drawdown check (even when no signal fired) ───────────
        AccountService.AccountSnapshot snap = accountService.getSnapshot();
        if (snap.netLiquidationValue().compareTo(BigDecimal.ZERO) > 0) {
            riskManager.evaluateDrawdown(snap.netLiquidationValue());
        }
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
