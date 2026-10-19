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
 * <p>The strike ladder is generated from configured spacing; expiry is discovered separately
 * from a configurable increment rather than discovered. We generate <b>ATM + OTM only</b>
 * (in-the-money strikes are intentionally excluded): the near-the-money anchor plus
 * {@code strikesPerSide} out-of-the-money strikes stepping outward.</p>
 * <ul>
 *   <li><b>CALL</b>: OTM = strikes ABOVE spot.</li>
 *   <li><b>PUT</b>:  OTM = strikes BELOW spot.</li>
 * </ul>
 *
 * <p>The anchor is the strike-ladder step nearest to spot (rounded to the increment). It is
 * included only when it is ATM or OTM for the side (never when it rounds to an ITM strike), so
 * no ITM contract is ever emitted. Generated strikes that aren't actually listed by the exchange
 * simply return no bars downstream — handled by the caller, not here.</p>
 */
public final class OccSymbolGenerator {

    private OccSymbolGenerator() {}

    /**
     * Generates the ATM + OTM contracts for ONE side (CALL or PUT) around {@code spot}.
     * In-the-money strikes are excluded.
     *
     * @param underlying     underlying root (e.g. AAPL)
     * @param spot           current underlying price (positive)
     * @param expiry         expiration date
     * @param type           CALL or PUT
     * @param increment      strike spacing (positive)
     * @param strikesPerSide how many OTM strikes to take beyond the anchor (>= 1)
     * @return the generated contracts (anchor first if ATM/OTM, then OTM outward)
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

        List<OptionContract> out = new ArrayList<>(strikesPerSide + 1);

        // Include the anchor only when it is ATM or OTM for this side (never ITM).
        Moneyness anchorMoneyness = moneynessOf(type, anchor, spot);
        if (anchorMoneyness != Moneyness.ITM) {
            addIfValid(out, underlying, expiry, type, anchor, anchorMoneyness);
        }

        // Walk OUTWARD from the anchor in the OTM direction: UP for CALL, DOWN for PUT.
        for (int i = 1; i <= strikesPerSide; i++) {
            BigDecimal step = increment.multiply(BigDecimal.valueOf(i));
            BigDecimal strike = (type == Type.CALL)
                    ? anchor.add(step).stripTrailingZeros()        // CALL OTM = above
                    : anchor.subtract(step).stripTrailingZeros();  // PUT  OTM = below
            addIfValid(out, underlying, expiry, type, strike, Moneyness.OTM);
        }
        return out;
    }

    /** Classifies a strike vs spot for the given side (ATM when equal). */
    private static Moneyness moneynessOf(Type type, BigDecimal strike, BigDecimal spot) {
        int cmp = strike.compareTo(spot);
        if (cmp == 0) return Moneyness.ATM;
        if (type == Type.CALL) {
            return cmp < 0 ? Moneyness.ITM : Moneyness.OTM;   // CALL: below spot = ITM, above = OTM
        }
        return cmp > 0 ? Moneyness.ITM : Moneyness.OTM;       // PUT:  above spot = ITM, below = OTM
    }

    private static void addIfValid(List<OptionContract> out, String underlying, LocalDate expiry,
                                   Type type, BigDecimal strike, Moneyness moneyness) {
        if (strike.compareTo(BigDecimal.ZERO) <= 0) return;   // skip non-positive strikes near low-priced names
        out.add(OptionContract.of(underlying, expiry, type, strike, moneyness));
    }

}
