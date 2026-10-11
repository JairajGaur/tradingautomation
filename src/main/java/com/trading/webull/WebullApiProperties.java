package com.trading.webull;

import com.trading.config.AppProperties;
import com.trading.config.BrokerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Webull-adapter-scoped view of the Webull-specific configuration: API credentials,
 * v3 endpoint paths, and the resolved API host.
 *
 * <p>This is the ONLY configuration surface the Webull adapter
 * ({@link WebullV3Client}) and the {@link com.trading.config.CredentialsValidator}
 * depend on. It exposes just the broker-specific settings ({@link BrokerProperties})
 * plus the resolved host — not the app's neutral trading/risk/exit/strategy/alert
 * configuration — so that broker credentials and routing are isolated to the adapter.
 * A different broker's adapter has its own analogous properties type; nothing
 * broker-agnostic reads this.</p>
 *
 * <p>Host resolution needs the app's trading mode, so this wrapper reads it from
 * {@link AppProperties} while all broker-specific fields come from
 * {@link BrokerProperties}.</p>
 */
@Component
@ConditionalOnProperty(name = "broker.provider", havingValue = "webull", matchIfMissing = true)
public class WebullApiProperties {

    private final BrokerProperties brokerProps;
    private final AppProperties appProps;

    public WebullApiProperties(BrokerProperties brokerProps, AppProperties appProps) {
        this.brokerProps = brokerProps;
        this.appProps = appProps;
    }

    /** Webull API credentials + options ({@code broker.webull.api.*}). */
    public BrokerProperties.Api api() {
        return brokerProps.api();
    }

    /** Webull v3 endpoint paths ({@code broker.webull.endpoints.*}). */
    public BrokerProperties.Endpoints endpoints() {
        return brokerProps.endpoints();
    }

    /**
     * Resolved API host: an explicit {@code broker.webull.api.endpoint} override when
     * set, otherwise the production or sandbox host selected by {@code app.engine.mode}.
     */
    public String resolvedApiHost() {
        String endpoint = brokerProps.api().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return appProps.isLiveMode()
                ? "api.webull.com"
                : "api.sandbox.webull.com";
    }
}
