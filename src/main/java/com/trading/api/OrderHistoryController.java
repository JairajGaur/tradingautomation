package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.webull.WebullV3Client;
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
 * REST API — Order history (Webull v3 via {@link WebullV3Client}).
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
    private final WebullV3Client client;

    public OrderHistoryController(WebullProperties props, WebullV3Client client) {
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

            WebullV3Client.V3Response resp = "open".equals(which)
                    ? client.openOrders(accountId, pageSize, lastId)
                    : client.orderHistory(accountId, pageSize, lastId);
            if (!resp.success()) {
                return badGateway(WebullErrors.fromResponse(resp));
            }

            JsonNode orders = resp.body() != null && resp.body().has("orders")
                    ? resp.body().get("orders")
                    : resp.body();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orders",    orders);
            body.put("count",     orders != null && orders.isArray() ? orders.size() : 0);
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("[OrderHistoryController] Error fetching orders", e);
            return badGateway(WebullErrors.fromThrowable(e));
        }
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
