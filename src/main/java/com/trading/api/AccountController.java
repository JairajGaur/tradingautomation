package com.trading.api;

import com.trading.config.AppProperties;
import com.trading.webull.WebullApiProperties;
import com.trading.service.AccountService;
import com.trading.service.MarketHoursGuard;
import com.trading.broker.BrokerClient;
import com.trading.service.RiskManager;
import com.trading.state.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API — Account information endpoints (broker API via {@link BrokerClient}).
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AppProperties props;
    private final WebullApiProperties brokerApi;
    private final BrokerClient client;
    private final AccountService accountService;
    private final RiskManager riskManager;
    private final MarketHoursGuard marketHoursGuard;
    private final PositionTracker positionTracker;

    public AccountController(AppProperties props,
                              WebullApiProperties brokerApi,
                              BrokerClient client,
                              AccountService accountService,
                              RiskManager riskManager,
                              MarketHoursGuard marketHoursGuard,
                              PositionTracker positionTracker) {
        this.props = props;
        this.brokerApi = brokerApi;
        this.client = client;
        this.accountService = accountService;
        this.riskManager = riskManager;
        this.marketHoursGuard = marketHoursGuard;
        this.positionTracker = positionTracker;
    }

    // -----------------------------------------------------------------------
    // GET /api/account/summary
    // -----------------------------------------------------------------------

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        log.info("[AccountController] GET /api/account/summary");

        AccountService.AccountSnapshot snap = accountService.refresh();
        Map<String, Object> webullError = null;
        com.trading.broker.model.AccountBalance balance = null;

        try {
            String accountId = client.resolveAccountId();
            if (accountId == null) {
                webullError = Map.of("kind", "NO_ACCOUNT",
                        "message", "Could not resolve account ID from /trading/accounts/list");
            } else {
                balance = client.fetchBalance(accountId);
                if (!balance.valid()) {
                    webullError = Map.of("kind", "HTTP_ERROR",
                            "message", "Balance request failed");
                }
            }
        } catch (com.trading.broker.BrokerException e) {
            webullError = WebullErrors.fromBroker(e);
            log.error("[AccountController] summary error", e);
        } catch (Exception e) {
            webullError = WebullErrors.fromThrowable(e);
            log.error("[AccountController] summary error", e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("netLiquidationValue", snap.netLiquidationValue().toPlainString());
        body.put("buyingPower",         snap.buyingPower().toPlainString());
        body.put("balanceValid",        balance != null && balance.valid());
        body.put("tradingHalted",       riskManager.isTradingHalted());
        body.put("marketSession",       marketHoursGuard.currentSessionDescription());
        body.put("tradingAllowed",      marketHoursGuard.isTradingAllowed());
        body.put("openPositions",       positionTracker.allPositions().size());
        body.put("mode",                props.engine().mode().name());
        body.put("endpoint",            brokerApi.resolvedApiHost());
        if (webullError != null) body.put("webullError", webullError);
        body.put("timestamp", TimeFormat.nowEt());

        return webullError == null
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // -----------------------------------------------------------------------
    // GET /api/account/balance
    // -----------------------------------------------------------------------

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        log.info("[AccountController] GET /api/account/balance");

        AccountService.AccountSnapshot snap = accountService.refresh();
        Map<String, Object> webullError = null;
        com.trading.broker.model.AccountBalance balance = null;

        try {
            String accountId = client.resolveAccountId();
            if (accountId == null) {
                webullError = Map.of("kind", "NO_ACCOUNT",
                        "message", "Could not resolve account ID from /trading/accounts/list");
            } else {
                balance = client.fetchBalance(accountId);
                if (!balance.valid()) {
                    webullError = Map.of("kind", "HTTP_ERROR",
                            "message", "Balance request failed");
                }
            }
        } catch (com.trading.broker.BrokerException e) {
            webullError = WebullErrors.fromBroker(e);
            log.error("[AccountController] balance error", e);
        } catch (Exception e) {
            webullError = WebullErrors.fromThrowable(e);
            log.error("[AccountController] balance error", e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("netLiquidationValue", snap.netLiquidationValue().toPlainString());
        body.put("buyingPower",         snap.buyingPower().toPlainString());
        body.put("balanceValid",        balance != null && balance.valid());
        body.put("mode",                props.engine().mode().name());
        body.put("endpoint",            brokerApi.resolvedApiHost());
        if (webullError != null) body.put("webullError", webullError);
        body.put("timestamp", TimeFormat.nowEt());

        return webullError == null
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // -----------------------------------------------------------------------
    // GET /api/account/status
    // -----------------------------------------------------------------------

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        log.info("[AccountController] GET /api/account/status");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tradingHalted",    riskManager.isTradingHalted());
        body.put("tradingAllowed",   marketHoursGuard.isTradingAllowed());
        body.put("marketSession",    marketHoursGuard.currentSessionDescription());
        body.put("openPositions",    positionTracker.allPositions().size());
        body.put("mode",             props.engine().mode().name());
        body.put("endpoint",         brokerApi.resolvedApiHost());
        body.put("maxDrawdownPct",   props.risk().maxDailyDrawdownPct().toPlainString());
        body.put("minBuyingPower",   props.risk().minBuyingPowerUsd().toPlainString());
        body.put("extendedHours",    props.marketHours().extendedHoursEnabled());
        body.put("timestamp",        TimeFormat.nowEt());

        return ResponseEntity.ok(body);
    }
}
