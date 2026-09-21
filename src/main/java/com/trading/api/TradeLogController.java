package com.trading.api;

import com.trading.service.TradeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API — Triggered-trade log.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/trades</td>
 *       <td>Recent trades the bot triggered, tagged with the firing strategy</td></tr>
 * </table>
 *
 * <p>This answers "was a trade triggered, and by which strategy?" — every BUY,
 * stop-loss, take-profit and emergency SELL is recorded with its strategy name.</p>
 */
@RestController
@RequestMapping("/api/trades")
public class TradeLogController {

    private static final Logger log = LoggerFactory.getLogger(TradeLogController.class);

    private final TradeLog tradeLog;

    public TradeLogController(TradeLog tradeLog) {
        this.tradeLog = tradeLog;
    }

    /**
     * Returns recent triggered trades, newest first.
     *
     * <p>Example — {@code GET /api/trades?limit=20}:
     * <pre>{@code
     * {
     *   "count": 2,
     *   "trades": [
     *     {
     *       "timestamp": "2024-01-15T14:30:00Z",
     *       "strategy": "600-EMA Momentum",
     *       "action": "BUY",
     *       "ticker": "AAPL",
     *       "quantity": 1,
     *       "price": null,
     *       "orderType": "MARKET",
     *       "orderId": "ord_abc",
     *       "mode": "PAPER",
     *       "success": true,
     *       "message": ""
     *     }
     *   ],
     *   "timestamp": "2024-01-15T14:30:05Z"
     * }
     * }</pre>
     *
     * @param limit max entries to return (default 50)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTrades(
            @RequestParam(defaultValue = "50") int limit) {

        log.info("[TradeLogController] GET /api/trades limit={}", limit);

        List<Map<String, Object>> trades = new ArrayList<>();
        for (TradeLog.TradeEntry e : tradeLog.recent(limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", e.timestamp().toString());        // UTC
            m.put("timestampEt", TimeFormat.toEt(e.timestamp())); // America/New_York
            m.put("strategy", e.strategy());
            m.put("action", e.action());
            m.put("ticker", e.ticker());
            m.put("quantity", e.quantity());
            m.put("price", e.price() != null ? e.price().toPlainString() : null);
            m.put("orderType", e.orderType());
            m.put("clientOrderId", e.clientOrderId());
            m.put("orderId", e.orderId());
            m.put("mode", e.mode());
            m.put("success", e.success());
            m.put("message", e.message());
            trades.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", trades.size());
        body.put("trades", trades);
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }
}
