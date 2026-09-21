package com.trading.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup validator for Webull API credentials.
 *
 * <p>Runs during application context initialisation ({@link PostConstruct}) — before
 * the Webull SDK client beans attempt to authenticate. If the app key or app secret
 * is missing, blank, or still contains an unresolved {@code ${...}} placeholder
 * (which happens when the environment variable is not set), the application refuses
 * to start with a clear, actionable error message.</p>
 *
 * <p>The credentials are expected to be injected from the {@code WEBULL_APP_KEY} and
 * {@code WEBULL_APP_SECRET} environment variables via {@code application.yml}.</p>
 */
@Component
public class CredentialsValidator {

    private static final Logger log = LoggerFactory.getLogger(CredentialsValidator.class);

    private final WebullProperties props;

    public CredentialsValidator(WebullProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void validate() {
        String appKey    = props.api().appKey();
        String appSecret = props.api().appSecret();

        boolean keyMissing    = isMissing(appKey);
        boolean secretMissing = isMissing(appSecret);

        if (keyMissing || secretMissing) {
            String message = buildErrorMessage(keyMissing, secretMissing);
            log.error(message);
            // Throwing here aborts context startup — the application will not run.
            throw new IllegalStateException(message);
        }

        log.info("[CredentialsValidator] Webull API credentials present " +
                        "(appKey length={}, appSecret length={}) — startup allowed.",
                appKey.trim().length(), appSecret.trim().length());
    }

    /**
     * A credential is considered missing if it is null, blank, or still an
     * unresolved Spring placeholder such as {@code ${WEBULL_APP_KEY}} — the latter
     * occurs when the environment variable was never set.
     */
    private boolean isMissing(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }

    private String buildErrorMessage(boolean keyMissing, boolean secretMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("============================================================\n");
        sb.append(" STARTUP ABORTED — Webull API credentials are missing.\n");
        sb.append("============================================================\n");
        if (keyMissing) {
            sb.append("  • WEBULL_APP_KEY is not set (webull.api.app-key is empty).\n");
        }
        if (secretMissing) {
            sb.append("  • WEBULL_APP_SECRET is not set (webull.api.app-secret is empty).\n");
        }
        sb.append("\n");
        sb.append("  Set both environment variables before starting the bot:\n");
        sb.append("\n");
        sb.append("    export WEBULL_APP_KEY=your_app_key_here\n");
        sb.append("    export WEBULL_APP_SECRET=your_app_secret_here\n");
        sb.append("\n");
        sb.append("  Then re-run:  ./gradlew bootRun\n");
        sb.append("============================================================");
        return sb.toString();
    }
}
