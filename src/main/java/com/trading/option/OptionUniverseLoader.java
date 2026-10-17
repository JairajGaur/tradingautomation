package com.trading.option;

import com.trading.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the configured <b>option underlyings</b> from
 * {@code app.engine.option-tickers-path} (default {@code classpath:option-tickers.txt}) —
 * the options mirror of {@link com.trading.config.UniverseLoader}.
 *
 * <p>The file lists UNDERLYING STOCK symbols (e.g. {@code AAPL}), NOT OCC codes. For each
 * underlying the options service pulls the current spot and generates the nearest ITM/OTM
 * strikes (see {@link OccSymbolGenerator}) to build the OCC symbols it loads bars for.</p>
 *
 * <h2>File format</h2>
 * One underlying per line. A line may optionally carry a per-symbol strike increment,
 * comma-separated — so each symbol is configured in exactly one place:
 * <pre>{@code
 *   SYMBOL                 # use the default increment (app.options.default-strike-increment)
 *   SYMBOL,<increment>     # override the strike spacing for THIS symbol only, e.g. TSLA,2.5
 * }</pre>
 * {@code #} comments and blank lines are ignored; symbols are upper-cased and de-duplicated.
 * A malformed increment is logged and the symbol falls back to the default.
 *
 * <p>An empty/missing file returns an empty universe — callers simply have nothing to pull.</p>
 */
@Component
public class OptionUniverseLoader {

    private static final Logger log = LoggerFactory.getLogger(OptionUniverseLoader.class);

    private final List<String> underlyings;
    private final Map<String, BigDecimal> strikeIncrements;

    public OptionUniverseLoader(AppProperties props, ResourceLoader resourceLoader) {
        Map<String, BigDecimal> parsed = load(props, resourceLoader);
        this.strikeIncrements = Collections.unmodifiableMap(parsed);
        this.underlyings = List.copyOf(parsed.keySet());
    }

    /** Unmodifiable list of configured option UNDERLYING symbols (may be empty). */
    public List<String> getUnderlyings() {
        return underlyings;
    }

    /**
     * The per-symbol strike increment parsed from the file for {@code underlying}, or
     * {@code null} when the line carried no override (caller should use the default).
     */
    public BigDecimal strikeIncrementFor(String underlying) {
        if (underlying == null) return null;
        return strikeIncrements.get(underlying.trim().toUpperCase());
    }

    /** Parses the file into an insertion-ordered map of symbol → increment (null = no override). */
    private Map<String, BigDecimal> load(AppProperties props, ResourceLoader resourceLoader) {
        String path = props.engine().optionTickersPath();
        log.info("[OptionUniverseLoader] Loading option underlyings from: {}", path);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("[OptionUniverseLoader] Option-tickers file not found at '{}' — empty universe", path);
                return out;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    parseLine(trimmed, out);
                }
            }
            log.info("[OptionUniverseLoader] Loaded {} option underlyings: {}", out.size(), out);
            return out;
        } catch (Exception e) {
            log.error("[OptionUniverseLoader] Failed to read option-tickers file '{}' — empty universe", path, e);
            return out;
        }
    }

    /** Parses one {@code SYMBOL[,increment]} line into {@code out} (last wins on duplicates). */
    private void parseLine(String trimmed, Map<String, BigDecimal> out) {
        String[] parts = trimmed.split(",", 2);
        String symbol = parts[0].trim().toUpperCase();
        if (symbol.isEmpty()) return;

        BigDecimal increment = null;
        if (parts.length == 2 && !parts[1].isBlank()) {
            try {
                BigDecimal inc = new BigDecimal(parts[1].trim());
                if (inc.compareTo(BigDecimal.ZERO) > 0) {
                    increment = inc;
                } else {
                    log.error("[OptionUniverseLoader] {} increment '{}' is not positive — using default",
                            symbol, parts[1].trim());
                }
            } catch (NumberFormatException e) {
                log.error("[OptionUniverseLoader] {} has an invalid increment '{}' — using default",
                        symbol, parts[1].trim());
            }
        }
        out.put(symbol, increment);
    }
}
