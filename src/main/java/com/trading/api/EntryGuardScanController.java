package com.trading.api;

import com.trading.config.WatchlistLoader;
import com.trading.config.WebullProperties;
import com.trading.service.BarDataManager;
import com.trading.service.EntryGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * REST API — evaluates tickers against the universal {@link EntryGuard} and reports
 * which ones currently PASS.
 *
 * <p>This is a read-only diagnostic: it calls {@link EntryGuard#evaluate(String)} — the
 * exact logic that gates every BUY — so the results match what the bot would actually
 * allow right now, under the current {@code webull.entry-guard.*} config (Supertrend
 * timespan, EMA stack, AND/OR logic). It does NOT arm tickers or place orders.</p>
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/entry-guard/scan</td>
 *       <td>Evaluate the watchlist (or an explicit list) and list the passers</td></tr>
 * </table>
 *
 * <p>Query params:</p>
 * <ul>
 *   <li>{@code tickers} — optional comma-separated symbols to scan instead of the
 *       watchlist (e.g. {@code ?tickers=AAPL,MSFT,TSLA}). Any US symbol works.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/entry-guard")
public class EntryGuardScanController {

    private static final Logger log = LoggerFactory.getLogger(EntryGuardScanController.class);

    private final WebullProperties props;
    private final WatchlistLoader watchlistLoader;
    private final EntryGuard entryGuard;
    private final BarDataManager barData;

    public EntryGuardScanController(WebullProperties props,
                                    WatchlistLoader watchlistLoader,
                                    EntryGuard entryGuard,
                                    BarDataManager barData) {
        this.props = props;
        this.watchlistLoader = watchlistLoader;
        this.entryGuard = entryGuard;
        this.barData = barData;
    }

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(required = false) String tickers) {

        // Resolve the symbols to scan: explicit ?tickers=... wins, else the watchlist.
        List<String> symbols = resolveSymbols(tickers);
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tickers to scan (watchlist empty and no ?tickers= provided)",
                    "timestamp", TimeFormat.nowEt()));
        }

        WebullProperties.EntryGuard cfg = props.entryGuard();
        log.info("[EntryGuardScan] Scanning {} ticker(s) — stTf={} emaTf={} requireMid={} logic={}",
                symbols.size(), cfg.supertrendTimespan(), cfg.emaTimespan(),
                cfg.emaRequireMid(), cfg.conditionLogic());

        // Prime the shared bar cache for the two timeframes the guard reads, so
        // first-time (non-watchlist) symbols are evaluated on real data, not empties.
        // Batch = one multi-symbol request per timeframe (cheap; skips already-fresh).
        int warmup = props.trading().warmupBars();
        try {
            barData.getBarsBatch(symbols, cfg.supertrendTimespan(), warmup);
            barData.getBarsBatch(symbols, cfg.emaTimespan(), warmup);
        } catch (Exception e) {
            log.warn("[EntryGuardScan] Bar prefetch failed (evaluation will use cache/on-demand): {}",
                    e.getMessage());
        }

        // Evaluate each symbol. evaluate() is read-only — it does not change armed state.
        List<String> passed = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            EntryGuard.Decision d;
            try {
                d = entryGuard.evaluate(symbol);
            } catch (Exception e) {
                log.warn("[EntryGuardScan] evaluate threw for {}: {}", symbol, e.getMessage());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticker", symbol);
                row.put("passed", false);
                row.put("reason", "EVAL_ERROR: " + e.getMessage());
                results.add(row);
                continue;
            }
            if (d.allowed()) {
                passed.add(symbol);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", symbol);
            row.put("passed", d.allowed());
            row.put("reason", d.reason());
            results.add(row);
        }

        Map<String, Object> guardConfig = new LinkedHashMap<>();
        guardConfig.put("enabled", cfg.enabled());
        guardConfig.put("supertrendTimespan", cfg.supertrendTimespan());
        guardConfig.put("emaTimespan", cfg.emaTimespan());
        guardConfig.put("emaFast", cfg.emaFast());
        guardConfig.put("emaMid", cfg.emaMid());
        guardConfig.put("emaSlow", cfg.emaSlow());
        guardConfig.put("emaRequireMid", cfg.emaRequireMid());
        guardConfig.put("conditionLogic", cfg.conditionLogic());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guardConfig", guardConfig);
        body.put("scanned", symbols.size());
        body.put("passedCount", passed.size());
        body.put("passed", passed);          // the list you asked for
        body.put("results", results);        // per-ticker pass/fail + reason
        body.put("mode", props.trading().mode().name());
        body.put("timestamp", TimeFormat.nowEt());
        return ResponseEntity.ok(body);
    }

    /**
     * Explicit {@code ?tickers=} list (comma-separated, upper-cased, de-duped) when
     * provided, otherwise the configured watchlist.
     */
    private List<String> resolveSymbols(String tickers) {
        if (tickers != null && !tickers.isBlank()) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String t : Arrays.asList(tickers.split(","))) {
                String s = t.trim().toUpperCase();
                if (!s.isEmpty()) set.add(s);
            }
            return new ArrayList<>(set);
        }
        return new ArrayList<>(watchlistLoader.getTickers());
    }
}
