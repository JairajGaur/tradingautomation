package com.trading;

import com.trading.alert.AlertEngine;
import com.trading.broker.BrokerClient;
import com.trading.service.AccountService;
import com.trading.service.MarketDataService;
import com.trading.service.OrderService;
import com.trading.webull.WebullV3Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Spring application context to prove all beans wire up after the
 * broker-abstraction refactor — including the Webull adapter selection and the
 * alert engine. No network calls are made (bean wiring only). Dummy credentials
 * satisfy the fail-fast {@code CredentialsValidator}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "webull.api.app-key=test-app-key",
        "webull.api.app-secret=test-app-secret",
        // Keep the automated loop off during the context test.
        "webull.trading.trading-enabled=false"
})
class ApplicationContextLoadTest {

    @Autowired
    private BrokerClient brokerClient;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AlertEngine alertEngine;

    @Test
    void contextLoadsAndBrokerClientIsWebullAdapter() {
        // Every core bean wired up.
        assertNotNull(brokerClient, "BrokerClient bean should be present");
        assertNotNull(marketDataService);
        assertNotNull(orderService);
        assertNotNull(accountService);
        assertNotNull(alertEngine, "AlertEngine (alerts path) should wire up");

        // With broker.provider unset, the Webull adapter is the active BrokerClient.
        assertTrue(brokerClient instanceof WebullV3Client,
                "Default BrokerClient should be the Webull adapter, was: "
                        + brokerClient.getClass().getName());
    }
}
