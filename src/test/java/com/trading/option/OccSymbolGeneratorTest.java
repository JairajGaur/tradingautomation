package com.trading.option;

import com.trading.option.OptionContract.Moneyness;
import com.trading.option.OptionContract.Type;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the pure OCC strike-ladder generation: ITM/OTM selection per side, OCC symbol
 * formatting, expiry resolution, and the OptionContract parse/build round-trip. No broker
 * needed — deterministic math.
 */
class OccSymbolGeneratorTest {

    private static final LocalDate EXP = LocalDate.of(2026, 5, 22);

    // ── OCC symbol format ────────────────────────────────────────────────

    @Test
    void buildsCanonicalOccSymbol() {
        OptionContract c = OptionContract.of("AAPL", EXP, Type.CALL, new BigDecimal("300"), Moneyness.ATM);
        assertEquals("AAPL260522C00300000", c.occSymbol());
    }

    @Test
    void encodesFractionalStrike() {
        OptionContract c = OptionContract.of("TSLA", LocalDate.of(2025, 12, 19), Type.PUT,
                new BigDecimal("452.5"), Moneyness.OTM);
        assertEquals("TSLA251219P00452500", c.occSymbol());
    }

    @Test
    void parseRoundTrips() {
        OptionContract c = OptionContract.parse("AAPL260522C00300000");
        assertEquals("AAPL", c.underlying());
        assertEquals(EXP, c.expiry());
        assertEquals(Type.CALL, c.type());
        assertEquals(0, new BigDecimal("300").compareTo(c.strike()));
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> OptionContract.parse("NOTANOCC"));
    }

    // ── Strike ladder: CALL ITM below spot, OTM above ─────────────────────

    @Test
    void callItmBelowSpotOtmAbove() {
        // spot 302.3, $1 increment → anchor 302. 5 per side.
        List<OptionContract> calls = OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("302.3"), EXP, Type.CALL, BigDecimal.ONE, 5);

        assertEquals(10, calls.size());
        long itm = calls.stream().filter(c -> c.moneyness() == Moneyness.ITM).count();
        long otm = calls.stream().filter(c -> c.moneyness() == Moneyness.OTM).count();
        assertEquals(5, itm);
        assertEquals(5, otm);

        // Every ITM call strike is below the anchor (302); every OTM strike above.
        for (OptionContract c : calls) {
            if (c.moneyness() == Moneyness.ITM) {
                assertTrue(c.strike().compareTo(new BigDecimal("302")) < 0, "ITM call strike below anchor");
            } else {
                assertTrue(c.strike().compareTo(new BigDecimal("302")) > 0, "OTM call strike above anchor");
            }
        }
        // Nearest ITM is 301, nearest OTM is 303.
        assertTrue(calls.stream().anyMatch(c -> c.strike().compareTo(new BigDecimal("301")) == 0));
        assertTrue(calls.stream().anyMatch(c -> c.strike().compareTo(new BigDecimal("303")) == 0));
    }

    // ── Strike ladder: PUT inverted (ITM above spot, OTM below) ───────────

    @Test
    void putItmAboveSpotOtmBelow() {
        List<OptionContract> puts = OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("302.0"), EXP, Type.PUT, BigDecimal.ONE, 5);

        assertEquals(10, puts.size());
        for (OptionContract c : puts) {
            if (c.moneyness() == Moneyness.ITM) {
                assertTrue(c.strike().compareTo(new BigDecimal("302")) > 0, "ITM put strike above anchor");
            } else {
                assertTrue(c.strike().compareTo(new BigDecimal("302")) < 0, "OTM put strike below anchor");
            }
        }
    }

    @Test
    void honoursStrikeIncrement() {
        // $2.5 increment around 450 → strikes land on 2.5 steps.
        List<OptionContract> calls = OccSymbolGenerator.generate(
                "TSLA", new BigDecimal("451"), EXP, Type.CALL, new BigDecimal("2.5"), 3);
        for (OptionContract c : calls) {
            BigDecimal rem = c.strike().remainder(new BigDecimal("2.5"));
            assertEquals(0, rem.compareTo(BigDecimal.ZERO), "strike on 2.5 ladder: " + c.strike());
        }
    }

    @Test
    void skipsNonPositiveStrikesForLowPricedNames() {
        // spot 3, $1 incr, 5 per side → the below-anchor strikes would go negative; those are skipped.
        List<OptionContract> calls = OccSymbolGenerator.generate(
                "CHEAP", new BigDecimal("3"), EXP, Type.CALL, BigDecimal.ONE, 5);
        assertTrue(calls.stream().allMatch(c -> c.strike().compareTo(BigDecimal.ZERO) > 0));
    }

    @Test
    void rejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("-1"), EXP, Type.CALL, BigDecimal.ONE, 5));
        assertThrows(IllegalArgumentException.class, () -> OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("300"), EXP, Type.CALL, BigDecimal.ZERO, 5));
    }

    // ── Expiry resolution (DTE-based) ─────────────────────────────────────

    @Test
    void zeroDteIsToday() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        assertEquals(today, OccSymbolGenerator.resolveExpiry(0, today));
    }

    @Test
    void dteAddsCalendarDays() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        assertEquals(LocalDate.of(2026, 10, 16), OccSymbolGenerator.resolveExpiry(1, today));
        assertEquals(LocalDate.of(2026, 10, 17), OccSymbolGenerator.resolveExpiry(2, today));
        // No weekend snapping — 3 DTE from Thu 10-15 is Sun 10-18, used literally.
        assertEquals(LocalDate.of(2026, 10, 18), OccSymbolGenerator.resolveExpiry(3, today));
    }

    @Test
    void negativeDteClampsToToday() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        assertEquals(today, OccSymbolGenerator.resolveExpiry(-5, today));
    }
}
