package com.trading.option;

import com.trading.broker.BrokerClient;
import com.trading.config.AppProperties;
import com.trading.model.Candle;
import com.trading.option.OptionContract.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the OPTION-CONTRACT pipeline: turning each configured UNDERLYING (from
 * {@code option-tickers.txt}) into a set of generated OCC contracts, loading their bars,
 * and holding the result in memory for inspection and for the ST/EMA endpoints.
 *
 * <p>Per underlying it: (1) reads the current spot via the broker's price snapshot
 * (same source the stock price endpoint uses); (2) resolves the expiry
 * ({@code app.options.expiry-date} else the nearest Friday ≥ {@code target-dte} days out);
 * (3) generates the nearest {@code strikes-per-side} ITM + {@code strikes-per-side} OTM
 * strikes for every configured side (CALL/PUT) via {@link OccSymbolGenerator}; (4) loads
 * option bars for the generated OCC symbols in batched requests.</p>
 *
 * <p><b>Error isolation:</b> a problem with ONE underlying or ONE contract (bad OCC,
 * no spot, no bars) is logged against that symbol and recorded in the result — it never
 * aborts the whole load, so every other valid contract still loads. This matches the
 * requirement: wrong OCC → visible in the logs for that symbol; the rest work.</p>
 */
@Service
public class OptionContractService {

    private static final Logger log = LoggerFactory.getLogger(OptionContractService.class);

    private final AppProperties props;
    private final OptionUniverseLoader underlyings;
    private final OptionBarDataManager barDataManager;
    private final BrokerClient brokerClient;

    /** In-memory store of the last load, keyed by underlying symbol. */
    private final Map<String, UnderlyingContracts> store = new ConcurrentHashMap<>();

    public OptionContractService(AppProperties props,
                                 OptionUniverseLoader underlyings,
                                 OptionBarDataManager barDataManager,
                                 BrokerClient brokerClient) {
        this.props = props;
        this.underlyings = underlyings;
        this.barDataManager = barDataManager;
        this.brokerClient = brokerClient;
    }

    // -----------------------------------------------------------------------
    // In-memory records
    // -----------------------------------------------------------------------

    /** One generated contract plus the bars loaded for it (and any per-contract error). */
    public record LoadedContract(OptionContract contract, int barCount, String error) {
        public boolean loaded() { return error == null && barCount > 0; }
    }

    /** The full result for one underlying: the spot/expiry used and the per-contract outcomes. */
    public record UnderlyingContracts(
            String underlying,
            BigDecimal spot,
            LocalDate expiry,
            BigDecimal strikeIncrement,
            List<LoadedContract> contracts,
            String error,
            java.time.Instant loadedAt
    ) {}

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /** Loads option contracts for EVERY configured underlying, replacing the in-memory store. */
    public List<UnderlyingContracts> loadAllConfigured() {
        List<String> syms = underlyings.getUnderlyings();
        List<UnderlyingContracts> out = new ArrayList<>(syms.size());
        for (String underlying : syms) {
            out.add(loadUnderlying(underlying));
        }
        return out;
    }

    /**
     * Loads (or reloads) option contracts for a single underlying using the configured
     * {@code app.options.sides} (both CALL and PUT by default). Used by the manual load endpoint.
     * Never throws — any failure is captured in the returned record's {@code error} fields.
     */
    public UnderlyingContracts loadUnderlying(String underlyingRaw) {
        return loadUnderlying(underlyingRaw, configuredSides());
    }

    /**
     * Loads (or reloads) option contracts for a single underlying, restricted to the given
     * {@code sides} (e.g. only CALL, only PUT, or both). Lets a caller (the vol+ST alert) load
     * just the side its stock-direction gate selected. Never throws.
     *
     * @param sides the option types to generate/load; {@code null}/empty → nothing generated
     */
    public UnderlyingContracts loadUnderlying(String underlyingRaw, java.util.Set<Type> sides) {
        String underlying = underlyingRaw == null ? "" : underlyingRaw.trim().toUpperCase();
        AppProperties.Options cfg = props.options();

        // 1. Spot price (same source as the stock price endpoint — non-throwing).
        BigDecimal spot;
        try {
            spot = brokerClient.fetchFillPrice(underlying, null);
        } catch (Exception e) {
            spot = null;
            log.warn("[OptionContractService] {} spot lookup threw: {}", underlying, e.getMessage());
        }
        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
            String err = "No spot price for underlying " + underlying + " — cannot generate strikes";
            log.error("[OptionContractService] {}", err);
            UnderlyingContracts uc = new UnderlyingContracts(
                    underlying, null, null, null, List.of(), err, java.time.Instant.now());
            store.put(underlying, uc);
            return uc;
        }

        // 2. Expiry (today + dte) + strike increment (per-symbol from the file, else default).
        LocalDate expiry = OccSymbolGenerator.resolveExpiry(cfg.dte(), LocalDate.now());
        BigDecimal increment = strikeIncrementFor(underlying, cfg);

        // 3. Generate contracts for the requested side(s). A bad OCC for one side/strike
        //    is logged and skipped; other contracts still generate.
        List<OptionContract> generated = new ArrayList<>();
        java.util.Set<Type> sideSet = (sides == null) ? java.util.Set.of() : sides;
        for (Type type : sideSet) {
            try {
                generated.addAll(OccSymbolGenerator.generate(
                        underlying, spot, expiry, type, increment, cfg.strikesPerSide()));
            } catch (IllegalArgumentException e) {
                log.error("[OptionContractService] {} {} strike generation failed (spot={} incr={}): {}",
                        underlying, type, spot, increment, e.getMessage());
            }
        }

        if (generated.isEmpty()) {
            String err = "No contracts generated for " + underlying;
            log.error("[OptionContractService] {}", err);
            UnderlyingContracts uc = new UnderlyingContracts(
                    underlying, spot, expiry, increment, List.of(), err, java.time.Instant.now());
            store.put(underlying, uc);
            return uc;
        }

        // 4. Load bars for the generated OCC symbols (batched, 20/request).
        List<LoadedContract> loaded = loadBarsFor(underlying, generated, cfg);

        long ok = loaded.stream().filter(LoadedContract::loaded).count();
        log.info("[OptionContractService] {} spot={} expiry={} incr={} → {} contracts, {} with data, {} empty",
                underlying, spot, expiry, increment, loaded.size(), ok, loaded.size() - ok);

        UnderlyingContracts uc = new UnderlyingContracts(
                underlying, spot, expiry, increment, loaded, null, java.time.Instant.now());
        store.put(underlying, uc);
        return uc;
    }

    /** Fetches bars for the generated contracts and maps each to a {@link LoadedContract}. */
    private List<LoadedContract> loadBarsFor(String underlying, List<OptionContract> generated,
                                             AppProperties.Options cfg) {
        List<String> occSymbols = new ArrayList<>(generated.size());
        for (OptionContract c : generated) occSymbols.add(c.occSymbol());

        // Refresh the shared cache for all generated contracts in batched (20/chunk),
        // throttled, boundary-aware fetches — then read each contract through the cache.
        // Pulls at the configured app.options.timespan / bar-count.
        barDataManager.getBarsBatch(occSymbols, cfg.timespan(), cfg.barCount());

        List<LoadedContract> out = new ArrayList<>(generated.size());
        for (OptionContract c : generated) {
            List<Candle> bars = barDataManager.getBars(c.occSymbol(), cfg.timespan(), cfg.barCount());
            if (bars != null && !bars.isEmpty()) {
                out.add(new LoadedContract(c, bars.size(), null));
            } else {
                // No data for a generated OCC — most often a strike/expiry that isn't actually
                // listed. Log it per-contract so a wrong OCC is visible for THAT symbol.
                String err = "No option bars for " + c.occSymbol()
                        + " (strike " + c.strike().toPlainString() + " " + c.type()
                        + " exp " + c.expiry() + ") — likely not a listed contract";
                log.error("[OptionContractService] {}", err);
                out.add(new LoadedContract(c, 0, err));
            }
        }
        return out;
    }

    /**
     * The strike increment for {@code underlying}: the per-symbol value from
     * {@code option-tickers.txt} when present, else {@code app.options.default-strike-increment}.
     */
    private BigDecimal strikeIncrementFor(String underlying, AppProperties.Options cfg) {
        BigDecimal fromFile = underlyings.strikeIncrementFor(underlying);
        if (fromFile != null && fromFile.compareTo(BigDecimal.ZERO) > 0) {
            return fromFile;
        }
        return cfg.defaultStrikeIncrement();
    }

    /**
     * Generates (but does NOT load bars for) the near-the-money contracts for {@code underlying}
     * off an EXPLICIT {@code spot} price and the given {@code sides}. Used by the replay, which
     * must pick strikes off the session OPEN price rather than the current spot. Never throws —
     * a bad strike is logged and skipped.
     */
    public List<OptionContract> generateContracts(String underlyingRaw, BigDecimal spot,
                                                  java.util.Set<Type> sides) {
        return generateContracts(underlyingRaw, spot, sides,
                LocalDate.now(java.time.ZoneId.of("America/New_York")));
    }

    /** Replay expiry must be resolved relative to the session, not the request date. */
    public List<OptionContract> generateContracts(String underlyingRaw, BigDecimal spot,
                                                  java.util.Set<Type> sides, LocalDate sessionDate) {
        String underlying = underlyingRaw == null ? "" : underlyingRaw.trim().toUpperCase();
        List<OptionContract> out = new ArrayList<>();
        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0 || sides == null || sides.isEmpty()) {
            return out;
        }
        AppProperties.Options cfg = props.options();
        LocalDate expiry = OccSymbolGenerator.resolveExpiry(cfg.dte(), sessionDate);
        BigDecimal increment = strikeIncrementFor(underlying, cfg);
        for (Type type : sides) {
            try {
                out.addAll(OccSymbolGenerator.generate(underlying, spot, expiry, type, increment, cfg.strikesPerSide()));
            } catch (IllegalArgumentException e) {
                log.error("[OptionContractService] {} {} replay strike generation failed (spot={} incr={}): {}",
                        underlying, type, spot, increment, e.getMessage());
            }
        }
        return out;
    }

    /** Parses {@code app.options.sides} into a set of {@link Type} (invalid tokens logged + skipped). */
    private java.util.Set<Type> configuredSides() {
        java.util.Set<Type> out = java.util.EnumSet.noneOf(Type.class);
        for (String sideStr : props.options().sides()) {
            try {
                out.add(Type.valueOf(sideStr.trim().toUpperCase()));
            } catch (Exception e) {
                log.error("[OptionContractService] invalid side '{}' in app.options.sides — skipping", sideStr);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Inspection
    // -----------------------------------------------------------------------

    /** The stored result for one underlying, or {@code null} if never loaded. */
    public UnderlyingContracts get(String underlying) {
        if (underlying == null) return null;
        return store.get(underlying.trim().toUpperCase());
    }

    /** All stored results, keyed by underlying. */
    public Map<String, UnderlyingContracts> all() {
        return Collections.unmodifiableMap(store);
    }
}
