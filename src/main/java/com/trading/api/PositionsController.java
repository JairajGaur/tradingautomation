package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.config.WebullProperties;
import com.trading.state.PositionTracker;
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
import java.util.List;
import java.util.Map;

/**
 * REST API — Positions endpoints (Webull v3 via {@link WebullV3Client}).
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

    private static final Logger log = LoggerFactory.getLogger(PositionsController.class);

    private final WebullProperties props;
    private final WebullV3Client client;
    private final PositionTracker positionTracker;

    public PositionsController(WebullProperties props,
                                WebullV3Client client,
                                PositionTracker positionTracker) {
        this.props = props;
        this.client = client;
        this.positionTracker = positionTracker;
    }

    /**
     * Live holdings from Webull ({@code /trading/assets/positions/list}).
     * Returns the raw v3 JSON so all fields are available to the caller.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPositions(
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String lastId) {

        log.info("[PositionsController] GET /api/positions pageSize={} lastId={}", pageSize, lastId);

        try {
            String accountId = client.resolveAccountId();
            if (accountId == null) {
                return badGateway(Map.of("kind", "NO_ACCOUNT",
                        "message", "Could not resolve account ID from /trading/accounts/list"));
            }

            WebullV3Client.V3Response resp = client.positions(accountId, pageSize, lastId);
            if (!resp.success()) {
                return badGateway(WebullErrors.fromResponse(resp));
            }

            JsonNode holdings = resp.body() != null && resp.body().has("holdings")
                    ? resp.body().get("holdings")
                    : resp.body();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("holdings",  holdings);
            body.put("count",     holdings != null && holdings.isArray() ? holdings.size() : 0);
            body.put("timestamp", TimeFormat.nowEt());
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("[PositionsController] Error fetching positions", e);
            return badGateway(WebullErrors.fromThrowable(e));
        }
    }

    /**
     * Positions tracked in-memory by the bot (opened by a strategy, not yet closed).
     */
    @GetMapping("/tracked")
    public ResponseEntity<Map<String, Object>> getTrackedPositions() {
        log.info("[PositionsController] GET /api/positions/tracked");

        List<Map<String, String>> tracked = positionTracker.allPositions().values().stream()
                .map(p -> Map.of(
                        "ticker",        p.ticker(),
                        "entryPrice",    p.entryPrice().toPlainString(),
                        "quantity",      String.valueOf(p.quantity()),
                        "stopPrice",     p.stopPrice().toPlainString(),
                        "targetPrice",   p.targetPrice().toPlainString(),
                        "clientOrderId", p.clientOrderId()
                ))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positions", tracked);
        body.put("count",     tracked.size());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badGateway(Map<String, Object> webullError) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Failed to fetch positions");
        err.put("mode", props.trading().mode().name());
        err.put("endpoint", props.resolvedApiHost());
        err.put("webullError", webullError);
        err.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err);
    }
}
