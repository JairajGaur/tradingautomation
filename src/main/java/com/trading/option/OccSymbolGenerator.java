package com.trading.option;

import com.trading.option.OptionContract.Moneyness;
import com.trading.option.OptionContract.Type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure (stateless) generator of option OCC symbols around the current spot price.
 *
 * <p>Webull's v3 API has NO option-chain endpoint, so the strike ladder is synthesised
 * from a configurable increment rather than discovered. For each side we take the
 * nearest {@code strikesPerSide} in-the-money and {@code strikesPerSide} out-of-the-money
 * strikes relative to spot:</p>
 * <ul>
 *   <li><b>CALL</b>: strikes BELOW spot are ITM, strikes ABOVE spot are OTM.</li>
 *   <li><b>PUT</b>: strikes ABOVE spot are ITM, strikes BELOW spot are OTM.</li>
 * </ul>
 *
 * <p>The anchor is the strike-ladder step nearest to spot (rounded to the increment).
 * A step that happens to equal spot is treated as ATM and counted on the OTM side so the
 * requested count on each side is still produced. Generated strikes that aren't actually
 * listed by the exchange simply return no bars downstream — that is handled by the caller,
 * not here.</p>
 */
public final class OccSymbolGenerator {

    private OccSymbolGenerator() {}

    /**
     * Generates the ITM + OTM contracts for ONE side (CALL or PUT) around {@code spot}.
     *
     * @param underlying     underlying root (e.g. AAPL)
     * @param spot           current underlying price (positive)
     * @param expiry         expiration date
     * @param type           CALL or PUT
     * @param increment      strike spacing (positive)
     * @param strikesPerSide how many ITM and how many OTM strikes to take (>= 1)
     * @return the generated contracts (ITM first, then OTM), each with a valid OCC symbol
     * @throws IllegalArgumentException if inputs are invalid
     */
    public static List<OptionContract> generate(String underlying, BigDecimal spot, LocalDate expiry,
                                                Type type, BigDecimal increment, int strikesPerSide) {
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("spot must be positive, was " + spot);
        }
        if (increment == null || increment.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("increment must be positive, was " + increment);
        }
        if (strikesPerSide < 1) {
            throw new IllegalArgumentException("strikesPerSide must be >= 1, was " + strikesPerSide);
        }

        // The strike-ladder step nearest to spot (rounded to the increment).
        BigDecimal steps = spot.divide(increment, 0, RoundingMode.HALF_UP);
        BigDecimal anchor = steps.multiply(increment).stripTrailingZeros();

        List<OptionContract> out = new ArrayList<>(strikesPerSide * 2);

        // ITM side: for CALL the strikes BELOW spot, for PUT the strikes ABOVE spot.
        // OTM side: the mirror. We walk outward from the anchor in increment steps.
        for (int i = 1; i <= strikesPerSide; i++) {
            BigDecimal below = anchor.subtract(increment.multiply(BigDecimal.valueOf(i))).stripTrailingZeros();
            BigDecimal above = anchor.add(increment.multiply(BigDecimal.valueOf(i))).stripTrailingZeros();
            if (type == Type.CALL) {
                addIfValid(out, underlying, expiry, type, below, Moneyness.ITM);
                addIfValid(out, underlying, expiry, type, above, Moneyness.OTM);
            } else {
                addIfValid(out, underlying, expiry, type, above, Moneyness.ITM);
                addIfValid(out, underlying, expiry, type, below, Moneyness.OTM);
            }
        }
        return out;
    }

    private static void addIfValid(List<OptionContract> out, String underlying, LocalDate expiry,
                                   Type type, BigDecimal strike, Moneyness moneyness) {
        if (strike.compareTo(BigDecimal.ZERO) <= 0) return;   // skip non-positive strikes near low-priced names
        out.add(OptionContract.of(underlying, expiry, type, strike, moneyness));
    }

    /**
     * Resolves the expiry from a days-to-expiry count: {@code today + dte} calendar days.
     * {@code 0DTE} → today, {@code 1DTE} → tomorrow, {@code 2DTE} → today+2, and so on.
     *
     * <p>No weekend/holiday snapping is applied — the literal date is used. If no contract
     * is actually listed on that date (e.g. a non-expiry weekday for a given symbol), the
     * generated OCC codes simply return no bars, which the caller logs per-contract. This
     * keeps expiry selection predictable and avoids guessing the exchange calendar.</p>
     *
     * @param dte   days to expiry (clamped at 0; negative is treated as 0)
     * @param today the reference date (normally {@code LocalDate.now()} in ET)
     */
    public static LocalDate resolveExpiry(int dte, LocalDate today) {
        return today.plusDays(Math.max(0, dte));
    }
}
