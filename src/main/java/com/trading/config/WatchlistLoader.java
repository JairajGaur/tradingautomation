package com.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads the ticker watchlist from a file at application startup.
 *
 * <p>The file path is configured via {@code webull.trading.watchlist-path} and
 * supports any Spring resource path:
 * <ul>
 *   <li>{@code classpath:tickers.txt} — bundled inside the JAR (default)</li>
 *   <li>{@code file:/data/my-tickers.txt} — external filesystem path</li>
 * </ul>
 *
 * <p>File format:
 * <ul>
 *   <li>One ticker symbol per line (e.g. {@code AAPL})</li>
 *   <li>Lines starting with {@code #} are treated as comments and ignored</li>
 *   <li>Blank lines are ignored</li>
 *   <li>Symbols are upper-cased and trimmed automatically</li>
 * </ul>
 *
 * <p>If the file cannot be loaded (missing, empty, or malformed), the loader
 * falls back to the single {@code webull.trading.ticker} value from config so
 * the bot can still start with a sensible default.
 */
@Component
public class WatchlistLoader {

    private static final Logger log = LoggerFactory.getLogger(WatchlistLoader.class);

    private final List<String> tickers;

    public WatchlistLoader(WebullProperties props, ResourceLoader resourceLoader) {
        this.tickers = load(props, resourceLoader);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns an unmodifiable list of ticker symbols in watchlist order.
     * Never null, never empty (falls back to the single configured ticker).
     */
    public List<String> getTickers() {
        return tickers;
    }

    // -----------------------------------------------------------------------
    // Loading logic
    // -----------------------------------------------------------------------

    private List<String> load(WebullProperties props, ResourceLoader resourceLoader) {
        String path = props.trading().watchlistPath();
        log.info("[WatchlistLoader] Loading ticker watchlist from: {}", path);

        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("[WatchlistLoader] Watchlist file not found at '{}' — falling back to single ticker: {}",
                        path, props.trading().ticker());
                return fallback(props);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                List<String> loaded = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(String::toUpperCase)
                        .distinct()
                        .collect(Collectors.toUnmodifiableList());

                if (loaded.isEmpty()) {
                    log.warn("[WatchlistLoader] Watchlist file '{}' is empty — falling back to single ticker: {}",
                            path, props.trading().ticker());
                    return fallback(props);
                }

                log.info("[WatchlistLoader] Loaded {} tickers: {}", loaded.size(), loaded);
                return loaded;
            }

        } catch (Exception e) {
            log.error("[WatchlistLoader] Failed to read watchlist file '{}' — falling back to single ticker: {}",
                    path, props.trading().ticker(), e);
            return fallback(props);
        }
    }

    private List<String> fallback(WebullProperties props) {
        return List.of(props.trading().ticker().toUpperCase());
    }
}
