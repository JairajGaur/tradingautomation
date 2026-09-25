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
 * Loads the candidate-ticker <b>universe</b> from {@code webull.trading.universe-path}
 * (default {@code classpath:universe.txt}). This pool is screened by the Supertrend
 * filter endpoint ({@code GET /api/filter/supertrend}); it is NOT the trading
 * watchlist. Same file format as the watchlist: one symbol per line, {@code #}
 * comments and blank lines ignored, symbols upper-cased and de-duplicated.
 *
 * <p>Unlike {@link WatchlistLoader}, an empty/missing universe returns an empty list
 * (there is no single-ticker fallback — the filter simply has nothing to screen).</p>
 */
@Component
public class UniverseLoader {

    private static final Logger log = LoggerFactory.getLogger(UniverseLoader.class);

    private final List<String> universe;

    public UniverseLoader(WebullProperties props, ResourceLoader resourceLoader) {
        this.universe = load(props, resourceLoader);
    }

    /** Unmodifiable list of candidate symbols (may be empty). */
    public List<String> getUniverse() {
        return universe;
    }

    private List<String> load(WebullProperties props, ResourceLoader resourceLoader) {
        String path = props.trading().universePath();
        log.info("[UniverseLoader] Loading ticker universe from: {}", path);
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("[UniverseLoader] Universe file not found at '{}' — empty universe", path);
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> loaded = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(String::toUpperCase)
                        .distinct()
                        .collect(Collectors.toUnmodifiableList());
                log.info("[UniverseLoader] Loaded {} universe symbols: {}", loaded.size(), loaded);
                return loaded;
            }
        } catch (Exception e) {
            log.error("[UniverseLoader] Failed to read universe file '{}' — empty universe", path, e);
            return List.of();
        }
    }
}
