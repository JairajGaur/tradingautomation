package com.trading.api;

import com.trading.broker.BrokerClient;
import com.trading.broker.model.OrderSummary;
import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — Order history (broker API via {@link BrokerClient}).
 *
 * <p>The v3 order-history endpoint ({@code /trading/orders/history}) returns
 * orders within a date range (default last 7 days). Both {@code /today} and
 * {@code /open} map to the same endpoint here — the caller can filter client-side
 * by status if needed.</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderHistoryController {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryController.class);

    private final WebullProperties props;
    private final BrokerClient client;

    public OrderHistoryController(WebullProperties props, BrokerClient client) {
        this.props = props;
        this.client = client;
    }

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> getTodayOrders(
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String lastId) {
        log.info("[OrderHistoryController] GET /api/orders/today pageSize={} lastId={}", pageSize, lastId);
        return fetch("history", pageSize, lastId);
    }

    @GetMapping("/open")
    public ResponseEntity<Map<String, Object>> getOpenOrders(
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String lastId) {
        log.info("[OrderHistoryController] GET /api/orders/open pageSize={} lastId={}", pageSize, lastId);
        return fetch("open", pageSize, lastId);
    }

    private ResponseEntity<Map<String, Object>> fetch(String which, int pageSize, String lastId) {
        try {
            String accountId = client.resolveAccountId();
            if (accountId == null) {
                return badGateway(Map.of("kind", "NO_ACCOUNT",
                        "message", "Could not resolve account ID from /trading/accounts/list"));
            }

            java.util.List<OrderSummary> orders = "open".equals(which)
                    ? client.fetchOpenOrders(accountId, pageSize, lastId)
                    : client.fetchOrderHistory(accountId, pageSize, lastId);

            java.util.List<Map<String, String>> mapped = orders.stream()
                    .map(OrderHistoryController::toMap)
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orders",    mapped);
            body.put("count",     mapped.size());
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);

        } catch (com.trading.broker.BrokerException e) {
            log.error("[OrderHistoryController] Error fetching orders", e);
            return badGateway(WebullErrors.fromBroker(e));
        } catch (Exception e) {
            log.error("[OrderHistoryController] Error fetching orders", e);
            return badGateway(WebullErrors.fromThrowable(e));
        }
    }

    /** Maps a neutral {@link OrderSummary} to a JSON map, omitting null fields. */
    private static Map<String, String> toMap(OrderSummary o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o.orderId() != null)        m.put("orderId", o.orderId());
        if (o.clientOrderId() != null)  m.put("clientOrderId", o.clientOrderId());
        if (o.symbol() != null)         m.put("symbol", o.symbol());
        if (o.side() != null)           m.put("side", o.side());
        if (o.orderType() != null)      m.put("orderType", o.orderType());
        if (o.status() != null)         m.put("status", o.status());
        if (o.quantity() != null)       m.put("quantity", o.quantity());
        if (o.filledQuantity() != null) m.put("filledQuantity", o.filledQuantity());
        if (o.limitPrice() != null)     m.put("limitPrice", o.limitPrice());
        if (o.stopPrice() != null)      m.put("stopPrice", o.stopPrice());
        return m;
    }

    private ResponseEntity<Map<String, Object>> badGateway(Map<String, Object> webullError) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Failed to fetch orders");
        err.put("mode", props.trading().mode().name());
        err.put("endpoint", props.resolvedApiHost());
        err.put("webullError", webullError);
        err.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err);
    }
}
