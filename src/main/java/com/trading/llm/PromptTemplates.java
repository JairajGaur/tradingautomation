package com.trading.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from {@code src/main/resources/prompts/} and fills in
 * {@code {placeholder}} tokens. Keeping prompts OUT of the Java code means they can be
 * edited, diffed, and tuned without recompiling, and each use-case prompt lives in its own
 * file ({@code alert-review.txt}, {@code analyze.txt}, {@code evaluate.txt}).
 *
 * <p>Templates are read from the classpath and cached after first load. Substitution is a
 * simple literal {@code {key}} → value replacement — no templating engine, no expression
 * evaluation, so a prompt can never execute code.</p>
 */
@Component
public class PromptTemplates {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplates.class);
    private static final String BASE = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Returns the raw template text for {@code name} (e.g. {@code "alert-review"}), loading
     * {@code prompts/<name>.txt} from the classpath on first use.
     *
     * @throws IllegalStateException if the template file is missing/unreadable
     */
    public String raw(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    /**
     * Loads a template and substitutes every {@code {key}} with its value from {@code vars}.
     * Unreferenced vars are ignored; unmatched {@code {placeholders}} are left as-is (so a
     * typo is visible in the output rather than silently blanked).
     */
    public String render(String name, Map<String, String> vars) {
        String out = raw(name);
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    private String load(String name) {
        String path = BASE + name + ".txt";
        ClassPathResource res = new ClassPathResource(path);
        try (InputStream in = res.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("[PromptTemplates] loaded {} ({} chars)", path, text.length());
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Missing/unreadable prompt template: " + path, e);
        }
    }
}
