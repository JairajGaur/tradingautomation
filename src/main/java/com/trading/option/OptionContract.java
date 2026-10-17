package com.trading.option;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * An option contract identified by its OCC symbol plus the parsed fields that compose it.
 *
 * <h2>OCC symbol layout</h2>
 * <pre>{@code <ROOT><YYMMDD><C|P><STRIKE × 1000, zero-padded to 8 digits>}</pre>
 * e.g. {@code AAPL260522C00300000} = underlying {@code AAPL}, expiry 2026-05-22, a
 * {@code CALL}, strike {@code 300.000}. The root is left-justified (no padding); the
 * strike is the price × 1000 as an 8-digit zero-padded integer (so it carries 3 decimals).
 *
 * <p>Webull's option market-data and order APIs both key off this OCC {@code symbol}.
 * This record is the single source of truth for building and parsing it, so the format
 * lives in exactly one place.</p>
 */
public record OptionContract(
        String occSymbol,
        String underlying,
        LocalDate expiry,
        Type type,
        BigDecimal strike,
        Moneyness moneyness
) {

    public enum Type {
        CALL('C'), PUT('P');
        private final char code;
        Type(char code) { this.code = code; }
        public char code() { return code; }
    }

    /** Position of the strike relative to spot (for the ITM/OTM selection). */
    public enum Moneyness { ITM, OTM, ATM }

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    public OptionContract {
        if (occSymbol == null || occSymbol.isBlank()) {
            throw new IllegalArgumentException("occSymbol must not be blank");
        }
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (strike == null || strike.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("strike must be positive");
        }
    }

    /**
     * Builds an OCC symbol from its parts and returns a validated {@link OptionContract}.
     *
     * @param underlying the underlying root (e.g. {@code AAPL}); upper-cased
     * @param expiry     expiration date
     * @param type       CALL or PUT
     * @param strike     strike price (positive; up to 3 decimals are preserved)
     * @param moneyness  classification relative to spot
     * @throws IllegalArgumentException if any part is invalid or the strike cannot be
     *                                  encoded in the 8-digit OCC strike field
     */
    public static OptionContract of(String underlying, LocalDate expiry, Type type,
                                    BigDecimal strike, Moneyness moneyness) {
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (strike == null || strike.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("strike must be positive, was " + strike);
        }

        String root = underlying.trim().toUpperCase();
        // Strike × 1000 as an integer; reject strikes finer than 1/1000 of a dollar.
        BigDecimal scaled = strike.multiply(BigDecimal.valueOf(1000));
        if (scaled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "strike " + strike + " has sub-1/1000 precision; cannot encode OCC strike field");
        }
        long strikeMils = scaled.longValueExact();
        if (strikeMils > 99_999_999L) {
            throw new IllegalArgumentException(
                    "strike " + strike + " too large to encode in the 8-digit OCC strike field");
        }
        String strikeField = String.format("%08d", strikeMils);
        String occ = root + expiry.format(YYMMDD) + type.code() + strikeField;

        return new OptionContract(occ, root, expiry, type, strike.stripTrailingZeros(), moneyness);
    }

    /**
     * Parses an OCC symbol back into its parts. Validates the structure; the moneyness
     * is unknown from the symbol alone, so it is set to {@code null}.
     *
     * @throws IllegalArgumentException if the symbol does not match the OCC layout
     */
    public static OptionContract parse(String occSymbol) {
        if (occSymbol == null) {
            throw new IllegalArgumentException("occSymbol must not be null");
        }
        String s = occSymbol.trim().toUpperCase();
        // Minimum length: 1-char root + 6 date + 1 type + 8 strike = 16.
        if (s.length() < 16) {
            throw new IllegalArgumentException("OCC symbol too short: '" + occSymbol + "'");
        }
        String strikeField = s.substring(s.length() - 8);
        char typeChar = s.charAt(s.length() - 9);
        String dateField = s.substring(s.length() - 15, s.length() - 9);
        String root = s.substring(0, s.length() - 15);

        if (root.isBlank() || !root.chars().allMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("Invalid OCC root in '" + occSymbol + "'");
        }
        if (!strikeField.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid OCC strike field in '" + occSymbol + "'");
        }
        Type type = switch (typeChar) {
            case 'C' -> Type.CALL;
            case 'P' -> Type.PUT;
            default -> throw new IllegalArgumentException(
                    "Invalid OCC type char '" + typeChar + "' in '" + occSymbol + "'");
        };
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(dateField, YYMMDD);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OCC date field '" + dateField + "' in '" + occSymbol + "'");
        }
        BigDecimal strike = new BigDecimal(strikeField)
                .divide(BigDecimal.valueOf(1000)).stripTrailingZeros();

        return new OptionContract(s, root, expiry, type, strike, null);
    }
}
