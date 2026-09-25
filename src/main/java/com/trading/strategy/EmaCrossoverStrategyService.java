package com.trading.strategy;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;
import com.trading.service.BarDataManager;
import com.trading.service.OrderService;
import com.trading.service.OrderService.OrderResult;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 100/20-EMA Golden-Cross Strategy — multi-ticker. <b>Entry-only.</b>
 *
 * <h2>Signal (per ticker, on the configured timeframe)</h2>
 * On the configured timeframe ({@code webull.strategies.ema-crossover-timeframe},
 * default M1), a golden cross is detected across the last two <em>completed</em>
 * bars: previous bar {@code ema20 ≤ ema100}, current bar {@code ema20 > ema100} →
 * market BUY. Only when no position is already open for the ticker.
 *
 * <p>There is no exit logic here — the universal {@code ExitManager} handles the
 * sell (−10% stop or 8/20 bearish cross). Enable/disable via
 * {@code webull.strategies.ema-crossover-enabled}.</p>
 */
@Service
public class EmaCrossoverStrategyService implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmaCrossoverStrategyService.class);

    private static final int PRICE_SCALE = 8;
    private static final int FAST_PERIOD = 20;
    private static final int SLOW_PERIOD = 100;

    private final WebullProperties props;
    private final BarDataManager barData;
    private final OrderService orderService;
    private final PositionTracker positionTracker;
    private final com.trading.service.VolumeFilter volumeFilter;
    private final com.trading.service.PendingSignals pendingSignals;
    private final com.trading.service.EntryGuard entryGuard;
    private final com.trading.service.TradeCooldown tradeCooldown;
    private final com.trading.service.QuantityManager quantityManager;
    private final com.trading.service.AccountService accountService;

    public EmaCrossoverStrategyService(WebullProperties props,
                                        BarDataManager barData,
                                        OrderService orderService,
                                        PositionTracker positionTracker,
                                        com.trading.service.VolumeFilter volumeFilter,
                                        com.trading.service.PendingSignals pendingSignals,
                                        com.trading.service.EntryGuard entryGuard,
                                        com.trading.service.TradeCooldown tradeCooldown,
                                        com.trading.service.QuantityManager quantityManager,
                                        com.trading.service.AccountService accountService) {
        this.props = props;
        this.barData = barData;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
        this.volumeFilter = volumeFilter;
        this.pendingSignals = pendingSignals;
        this.entryGuard = entryGuard;
        this.tradeCooldown = tradeCooldown;
        this.quantityManager = quantityManager;
        this.accountService = accountService;
    }

    @Override
    public String name() { return "100/20-EMA Crossover"; }

    @Override
    public void onCandle(Candle candle) {
        String ticker = candle.ticker();
        String tf = props.strategies().emaCrossoverTimeframe();

        if (positionTracker.hasOpenPosition(ticker)) {
            log.debug("[{}] ticker={} — position already open, skipping", name(), ticker);
            return;
        }
        if (pendingSignals.isPending(ticker)) {
            log.debug("[{}] ticker={} — signal already pending on volume, skipping", name(), ticker);
            return;
        }
        if (!tradeCooldown.canTrade(ticker)) {
            log.debug("[{}] ticker={} — in re-trade cooldown, skipping", name(), ticker);
            return;
        }

        // ENTRY GUARD FIRST — evaluate before computing the signal. If the guard fails
        // (e.g. 30m ST red), skip entirely: no signal computation, no BUY SIGNAL log.
        com.trading.service.EntryGuard.Decision guard = entryGuard.evaluate(ticker);
        if (!guard.allowed()) {
            log.debug("[{}] ticker={} — entry guard not satisfied ({}); skipping", name(), ticker, guard.reason());
            return;
        }

        List<Candle> bars = barData.getBars(ticker, tf, SLOW_PERIOD + 100);
        if (bars.size() < SLOW_PERIOD + 2) {
            log.debug("[{}] ticker={} not enough {} bars ({})", name(), ticker, tf, bars.size());
            return;
        }
        // Completed bars only (drop the last, possibly in-progress).
        List<Candle> completed = bars.subList(0, bars.size() - 1);
        List<BigDecimal> closesNow  = closes(completed);
        List<BigDecimal> closesPrev = closesNow.subList(0, closesNow.size() - 1);
        if (closesPrev.size() < SLOW_PERIOD) return;

        BigDecimal ema20Now  = EmaCalculator.calculate(closesNow, FAST_PERIOD);
        BigDecimal ema100Now = EmaCalculator.calculate(closesNow, SLOW_PERIOD);
        BigDecimal ema20Prev  = EmaCalculator.calculate(closesPrev, FAST_PERIOD);
        BigDecimal ema100Prev = EmaCalculator.calculate(closesPrev, SLOW_PERIOD);

        // Golden cross: previous completed bar ema20 <= ema100, current > .
        boolean goldenCross = ema20Prev.compareTo(ema100Prev) <= 0
                           && ema20Now.compareTo(ema100Now) > 0;
        if (!goldenCross) {
            log.debug("[{}] ticker={} — no golden cross ({}) ema20={} ema100={}",
                    name(), ticker, tf, ema20Now, ema100Now);
            return;
        }

        // Shared "don't buy the high" filter (config-gated, common to all strategies).
        BigDecimal lastClose = closesNow.get(closesNow.size() - 1);
        if (props.strategies().noBuyHighEnabled()
                && com.trading.indicator.EntryPriceFilter.isTooHigh(
                        closesNow, lastClose,
                        props.strategies().noBuyHighRefEma(),
                        props.strategies().noBuyHighMaxExtension())) {
            log.debug("[{}] ticker={} — price {} too extended above ema{} (max {}%); skipping",
                    name(), ticker, lastClose, props.strategies().noBuyHighRefEma(),
                    props.strategies().noBuyHighMaxExtension().multiply(BigDecimal.valueOf(100)));
            return;
        }

        // Shared "never buy below the EMA floor" filter (config-gated, all strategies).
        if (props.strategies().noBuyBelowEnabled()
                && com.trading.indicator.EntryPriceFilter.isBelowEma(
                        closesNow, lastClose, props.strategies().noBuyBelowRefEma())) {
            log.debug("[{}] ticker={} — price {} at/below ema{} floor; skipping",
                    name(), ticker, lastClose, props.strategies().noBuyBelowRefEma());
            return;
        }

        log.info("[{}] *** GOLDEN CROSS BUY SIGNAL *** ticker={} {} ema20={} crossed above ema100={} (guard passed)",
                name(), ticker, tf, ema20Now, ema100Now);

        // Common sizing via QuantityManager (SHARES or PERCENT-of-buying-power).
        // Price basis = marketable ask; fall back to the latest completed close.
        OrderService.Quote quote = orderService.fetchQuote(ticker);
        BigDecimal sizingPrice = quote.ask() != null
                ? quote.ask() : completed.get(completed.size() - 1).close();
        int qty = quantityManager.quantityFor(ticker, sizingPrice, accountService.getBuyingPowerLive());
        if (qty < 1) {
            log.info("[{}] ticker={} — sizing resolved to 0 shares; skipping", name(), ticker);
            return;
        }

        // Volume filter: only enter when average 1-min volume is increasing. If not,
        // HOLD the signal (re-checked for the configured window) instead of dropping.
        if (!volumeFilter.isVolumeIncreasing(ticker)) {
            if (pendingSignals.hold(ticker, name(), qty)) {
                log.info("[{}] ticker={} — golden cross but volume not increasing; holding", name(), ticker);
            } else {
                log.info("[{}] ticker={} — golden cross but volume not increasing; skipping (hold disabled)", name(), ticker);
            }
            return;
        }

        OrderResult buyResult = orderService.placeMarketBuy(ticker, qty, name());
        if (!buyResult.success()) {
            logBuyNotPlaced(ticker, buyResult.message());
            return;
        }

        BigDecimal fill = orderService.fetchFillPrice(ticker, completed.get(completed.size() - 1).open())
                               .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        positionTracker.openPosition(new Position(ticker, fill, qty, null, null, buyResult.clientOrderId()));
        tradeCooldown.record(ticker);   // start the cooldown on entry
        log.info("[{}] ENTERED ticker={} fill={} qty={} (exit handled by ExitManager)",
                name(), ticker, fill, qty);
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>(candles.size());
        for (Candle c : candles) out.add(c.close());
        return out;
    }

    /**
     * Logs why a BUY wasn't placed. Guard/spread/affordability gating is EXPECTED
     * (INFO, not an error); only genuine failures are logged at ERROR.
     */
    private void logBuyNotPlaced(String ticker, String message) {
        String m = message == null ? "" : message;
        if (m.startsWith("ENTRY_GUARD_NOT_ARMED") || m.startsWith("SPREAD_GUARD")
                || m.equals("INSUFFICIENT_BUYING_POWER") || m.equals("TRADING_HALTED")) {
            log.info("[{}] BUY not placed for ticker={}: {}", name(), ticker, m);
        } else {
            log.error("[{}] BUY failed for ticker={}: {}", name(), ticker, m);
        }
    }
}
