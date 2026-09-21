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
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 100/20-EMA Golden-Cross Strategy — multi-ticker.
 *
 * <h2>Signal rules (per ticker)</h2>
 * <ul>
 *   <li><b>BUY (golden cross):</b> On the previous candle {@code ema20 ≤ ema100};
 *       on the current candle {@code ema20 > ema100}.
 *       Only triggers when no position is already open for that ticker.</li>
 * </ul>
 *
 * <h2>Exit bracket — fixed-dollar amounts</h2>
 * <ul>
 *   <li><b>Take-Profit:</b> LIMIT SELL at fill price + $0.10</li>
 *   <li><b>Stop-Loss:</b>   STOP  SELL at fill price − $0.05</li>
 * </ul>
 *
 * <p>All order execution is delegated to {@link OrderService} — no Webull SDK
 * calls are made directly from this class.</p>
 *
 * <p>Enable/disable via {@code webull.strategies.ema-crossover-enabled=true|false}.</p>
 */
@Service
public class EmaCrossoverStrategyService implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmaCrossoverStrategyService.class);

    private static final int PRICE_SCALE = 8;

    private static final int FAST_PERIOD = 20;
    private static final int SLOW_PERIOD = 100;

    /** Fixed-dollar take-profit: +$0.10 per share. */
    private static final BigDecimal TAKE_PROFIT_OFFSET = new BigDecimal("0.10");
    /** Fixed-dollar stop-loss: −$0.05 per share. */
    private static final BigDecimal STOP_LOSS_OFFSET   = new BigDecimal("0.05");

    private final WebullProperties props;
    private final OrderService orderService;
    private final PositionTracker positionTracker;

    /**
     * Per-ticker crossover state.
     * Keeps both current and previous EMA values for crossover detection.
     */
    private record TickerState(
            BigDecimal ema20,
            BigDecimal ema100,
            BigDecimal prevEma20,
            BigDecimal prevEma100,
            boolean warmedUp
    ) {}

    private final Map<String, TickerState> stateByTicker = new ConcurrentHashMap<>();

    public EmaCrossoverStrategyService(WebullProperties props,
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
        if (historicalCloses.size() < SLOW_PERIOD) {
            log.warn("[{}] Warm-up skipped for ticker={} — {} bars available, {} needed",
                    name(), ticker, historicalCloses.size(), SLOW_PERIOD);
            return;
        }
        BigDecimal seedEma20  = EmaCalculator.calculate(historicalCloses, FAST_PERIOD);
        BigDecimal seedEma100 = EmaCalculator.calculate(historicalCloses, SLOW_PERIOD);

        // Seed prev == current so no spurious cross fires on the very first live candle
        stateByTicker.put(ticker, new TickerState(
                seedEma20, seedEma100, seedEma20, seedEma100, true));

        log.info("[{}] Warm-up complete: ticker={} ema20={} ema100={}",
                name(), ticker, seedEma20, seedEma100);
    }

    // -----------------------------------------------------------------------
    // Live candle processing
    // -----------------------------------------------------------------------

    @Override
    public String name() { return "100/20-EMA Crossover"; }

    @Override
    public void onCandle(Candle candle) {
        TickerState state = stateByTicker.get(candle.ticker());
        if (state == null || !state.warmedUp()) {
            log.debug("[{}] ticker={} not warmed up — skipping", name(), candle.ticker());
            return;
        }

        // 1. Capture previous values before updating
        BigDecimal prevEma20  = state.ema20();
        BigDecimal prevEma100 = state.ema100();

        // 2. Update both EMAs incrementally using this candle's close
        BigDecimal newEma20  = EmaCalculator.update(candle.close(), prevEma20,  FAST_PERIOD);
        BigDecimal newEma100 = EmaCalculator.update(candle.close(), prevEma100, SLOW_PERIOD);

        // 3. Persist updated state
        stateByTicker.put(candle.ticker(), new TickerState(
                newEma20, newEma100, prevEma20, prevEma100, true));

        log.debug("[{}] ticker={} close={} ema20={} ema100={}",
                name(), candle.ticker(), candle.close(), newEma20, newEma100);

        // 4. Golden-cross detection:
        //    previous bar: ema20 was at or below ema100 (no bull signal yet)
        //    current  bar: ema20 crossed above ema100 (golden cross confirmed)
        boolean goldenCross = prevEma20.compareTo(prevEma100) <= 0
                           && newEma20.compareTo(newEma100) > 0;

        if (!goldenCross) {
            log.debug("[{}] ticker={} — no golden cross this bar", name(), candle.ticker());
            return;
        }

        // 5. Position guard — no duplicate buys
        if (positionTracker.hasOpenPosition(candle.ticker())) {
            log.info("[{}] ticker={} — golden cross but position already open — skipping",
                    name(), candle.ticker());
            return;
        }

        log.info("[{}] *** GOLDEN CROSS BUY SIGNAL *** ticker={} ema20={} crossed above ema100={}",
                name(), candle.ticker(), newEma20, newEma100);

        // 6. Place market BUY via OrderService
        int qty = props.trading().orderQuantity();
        OrderResult buyResult = orderService.placeMarketBuy(candle.ticker(), qty, name());
        if (!buyResult.success()) {
            log.error("[{}] BUY failed for ticker={}: {}", name(), candle.ticker(), buyResult.message());
            return;
        }

        // 7. Fixed-dollar bracket prices
        BigDecimal fill   = candle.open().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal target = fill.add(TAKE_PROFIT_OFFSET).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal stop   = fill.subtract(STOP_LOSS_OFFSET).setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        log.info("[{}] Bracket: ticker={} fill={} target={} (+$0.10) stop={} (-$0.05)",
                name(), candle.ticker(), fill, target, stop);

        // 8. Register the open position
        positionTracker.openPosition(new Position(
                candle.ticker(), fill, qty, stop, target, buyResult.clientOrderId()));

        // 9. Place stop-loss SELL via OrderService
        OrderResult stopResult = orderService.placeStopSell(candle.ticker(), qty, stop, name());
        if (!stopResult.success()) {
            log.error("[{}] Stop-loss order failed for ticker={}: {}",
                    name(), candle.ticker(), stopResult.message());
        }

        // 10. Place take-profit limit SELL via OrderService
        OrderResult tpResult = orderService.placeLimitSell(candle.ticker(), qty, target, name());
        if (!tpResult.success()) {
            log.error("[{}] Take-profit order failed for ticker={}: {}",
                    name(), candle.ticker(), tpResult.message());
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public BigDecimal getCurrentEma20(String ticker) {
        TickerState s = stateByTicker.get(ticker);
        return s == null ? null : s.ema20();
    }

    public BigDecimal getCurrentEma100(String ticker) {
        TickerState s = stateByTicker.get(ticker);
        return s == null ? null : s.ema100();
    }

    public boolean isWarmedUp(String ticker) {
        TickerState s = stateByTicker.get(ticker);
        return s != null && s.warmedUp();
    }
}
