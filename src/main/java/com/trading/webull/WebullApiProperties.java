package com.trading.webull;

import com.trading.config.WebullProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Webull-adapter-scoped view of the Webull-specific configuration: API credentials,
 * v3 endpoint paths, and the resolved API host.
 *
 * <p>This is the ONLY configuration surface the Webull adapter
 * ({@link WebullV3Client}) and the {@link com.trading.config.CredentialsValidator}
 * depend on. It deliberately exposes just the broker-specific settings — not the
 * app's neutral trading/risk/exit/strategy/alert configuration — so that broker
 * credentials and routing are isolated to the adapter. A different broker's adapter
 * has its own analogous properties type; nothing broker-agnostic reads this.</p>
 *
 * <p>It is a thin wrapper over the app-wide {@link WebullProperties} binding rather
 * than a second {@code @ConfigurationProperties} class, so there is a single source
 * of truth for the {@code webull.*} YAML and no risk of divergent binding.</p>
 */
@Component
@ConditionalOnProperty(name = "broker.provider", havingValue = "webull", matchIfMissing = true)
public class WebullApiProperties {

    private final WebullProperties props;

    public WebullApiProperties(WebullProperties props) {
        this.props = props;
    }

    /** Webull API credentials + options ({@code webull.api.*}). */
    public WebullProperties.Api api() {
        return props.api();
    }

    /** Webull v3 endpoint paths ({@code webull.endpoints.*}). */
    public WebullProperties.Endpoints endpoints() {
        return props.endpoints();
    }

    /**
     * Resolved API host: an explicit {@code webull.api.endpoint} override when set,
     * otherwise the production or sandbox host selected by {@code webull.trading.mode}.
     */
    public String resolvedApiHost() {
        return props.resolvedApiHost();
    }
}
