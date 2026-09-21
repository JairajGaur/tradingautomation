package com.trading.strategy;

import com.trading.config.WebullProperties;
import com.trading.indicator.EmaCalculator;
import com.trading.model.Candle;
import com.trading.service.OrderService;
import com.trading.service.OrderService.OrderResult;
import com.trading.state.PositionTracker;
import com.trading.state.PositionTracker.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 600-EMA Momentum Strategy — multi-ticker.
 *
 * <h2>Signal rules (per ticker)</h2>
 * <ol>
 *   <li><b>Entry (BUY):</b> 1-minute candle open price is strictly above the
 *       600-period EMA → market BUY.</li>
 *   <li><b>Stop-Loss:</b> STOP SELL at 2 % below fill price.</li>
 *   <li><b>Take-Profit:</b> LIMIT SELL at 5 % above fill price.</li>
 * </ol>
 *
 * <p>All order execution is delegated to {@link OrderService} — no Webull SDK
 * calls are made directly from this class.</p>
 *
 * <p>Enable/disable via {@code webull.strategies.ema600-enabled=true|false}.</p>
 */
@Service
public class EmaStrategyService implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmaStrategyService.class);

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int PRICE_SCALE = 8;
    private static final BigDecimal STOP_LOSS_PCT   = new BigDecimal("0.02");
    private static final BigDecimal TAKE_PROFIT_PCT = new BigDecimal("0.05");

    private final WebullProperties props;
    private final OrderService orderService;
    private final PositionTracker positionTracker;

    /** Per-ticker EMA state. */
    private record TickerState(BigDecimal ema, boolean warmedUp) {}

    private final Map<String, TickerState> stateByTicker = new ConcurrentHashMap<>();

    public EmaStrategyService(WebullProperties props,
                               OrderService orderService,
                               PositionTracker positionTracker) {
        this.props = props;
        this.orderService = orderService;
        this.positionTracker = positionTracker;
    }

    // -----------------------------------------------------------------------
    // Warm-up
    // -----------------------------------------------------------------------

    @Override
    public void warmUp(String ticker, List<BigDecimal> historicalCloses) {
        int period = props.trading().emaPeriod();
        if (historicalCloses.size() < period) {
            log.warn("[{}] Warm-up skipped for ticker={} — {} bars available, {} needed",
                    name(), ticker, historicalCloses.size(), period);
            return;
        }
        BigDecimal seedEma = EmaCalculator.calculate(historicalCloses, period);
        stateByTicker.put(ticker, new TickerState(seedEma, true));
        log.info("[{}] Warm-up complete: ticker={} ema600={}", name(), ticker, seedEma);
    }

    // -----------------------------------------------------------------------
    // Live candle processing
    // -----------------------------------------------------------------------

    @Override
    public String name() { return "600-EMA Momentum"; }

    @Override
    public void onCandle(Candle candle) {
        TickerState state = stateByTicker.get(candle.ticker());
        if (state == null || !state.warmedUp()) {
            log.debug("[{}] ticker={} not warmed up — skipping", name(), candle.ticker());
            return;
        }

        // 1. Update EMA incrementally
        BigDecimal newEma = EmaCalculator.update(candle.close(), state.ema(), props.trading().emaPeriod());
        stateByTicker.put(candle.ticker(), new TickerState(newEma, true));

        log.debug("[{}] ticker={} close={} ema600={}", name(), candle.ticker(), candle.close(), newEma);

        // 2. Entry check: candle open must be strictly above EMA
        if (candle.open().compareTo(newEma) <= 0) {
            log.debug("[{}] ticker={} open={} not above ema={} — no entry",
                    name(), candle.ticker(), candle.open(), newEma);
            return;
        }

        // 3. Position guard — no duplicate buys
        if (positionTracker.hasOpenPosition(candle.ticker())) {
            log.info("[{}] ticker={} — BUY signal but position already open — skipping",
                    name(), candle.ticker());
            return;
        }

        log.info("[{}] *** BUY SIGNAL *** ticker={} open={} > ema600={}",
                name(), candle.ticker(), candle.open(), newEma);

        // 4. Place market BUY via OrderService
        int qty = props.trading().orderQuantity();
        OrderResult buyResult = orderService.placeMarketBuy(candle.ticker(), qty, name());
        if (!buyResult.success()) {
            log.error("[{}] BUY failed for ticker={}: {}", name(), candle.ticker(), buyResult.message());
            return;
        }

        // 5. Compute bracket prices (percentage-based)
        BigDecimal fill   = candle.open().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal stop   = fill.multiply(BigDecimal.ONE.subtract(STOP_LOSS_PCT, MC), MC)
                               .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal target = fill.multiply(BigDecimal.ONE.add(TAKE_PROFIT_PCT, MC), MC)
                               .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        log.info("[{}] Bracket: ticker={} fill={} stop={} (-2%) target={} (+5%)",
                name(), candle.ticker(), fill, stop, target);

        // 6. Register the open position
        positionTracker.openPosition(new Position(
                candle.ticker(), fill, qty, stop, target, buyResult.clientOrderId()));

        // 7. Place stop-loss SELL via OrderService
        OrderResult stopResult = orderService.placeStopSell(candle.ticker(), qty, stop, name());
        if (!stopResult.success()) {
            log.error("[{}] Stop-loss order failed for ticker={}: {}",
                    name(), candle.ticker(), stopResult.message());
        }

        // 8. Place take-profit limit SELL via OrderService
        OrderResult tpResult = orderService.placeLimitSell(candle.ticker(), qty, target, name());
        if (!tpResult.success()) {
            log.error("[{}] Take-profit order failed for ticker={}: {}",
                    name(), candle.ticker(), tpResult.message());
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public BigDecimal getCurrentEma(String ticker) {
        TickerState s = stateByTicker.get(ticker);
        return s == null ? null : s.ema();
    }

    public boolean isWarmedUp(String ticker) {
        TickerState s = stateByTicker.get(ticker);
        return s != null && s.warmedUp();
    }
}
