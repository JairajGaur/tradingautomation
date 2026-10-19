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
    void callAtmOtmOnly_noItm() {
        // spot 302.3, $1 increment → anchor 302 (which is BELOW spot → ITM for a call, so
        // excluded). Result: 5 OTM strikes 303..307, no ITM, no strike at/below spot.
        List<OptionContract> calls = OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("302.3"), EXP, Type.CALL, BigDecimal.ONE, 5);

        assertTrue(calls.stream().noneMatch(c -> c.moneyness() == Moneyness.ITM), "no ITM calls");
        // Every call strike is strictly above spot (OTM) — the ITM anchor 302 was dropped.
        for (OptionContract c : calls) {
            assertTrue(c.strike().compareTo(new BigDecimal("302.3")) > 0, "call strike above spot (OTM)");
        }
        assertEquals(5, calls.size());
        assertTrue(calls.stream().anyMatch(c -> c.strike().compareTo(new BigDecimal("303")) == 0));
        assertTrue(calls.stream().anyMatch(c -> c.strike().compareTo(new BigDecimal("307")) == 0));
    }

    @Test
    void callAtmIncludedWhenAnchorEqualsSpot() {
        // spot exactly on the ladder → anchor == spot → ATM (included) + 5 OTM above = 6.
        List<OptionContract> calls = OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("300"), EXP, Type.CALL, BigDecimal.ONE, 5);
        assertEquals(6, calls.size());
        assertEquals(1, calls.stream().filter(c -> c.moneyness() == Moneyness.ATM).count());
        assertTrue(calls.stream().noneMatch(c -> c.moneyness() == Moneyness.ITM));
        assertTrue(calls.stream().anyMatch(c -> c.strike().compareTo(new BigDecimal("300")) == 0 && c.moneyness() == Moneyness.ATM));
    }

    // ── Strike ladder: PUT — ATM + OTM below spot, no ITM ─────────────────

    @Test
    void putAtmOtmOnly_noItm() {
        // spot 302.0 on the ladder → anchor 302 == spot → ATM (included) + 5 OTM below.
        List<OptionContract> puts = OccSymbolGenerator.generate(
                "AAPL", new BigDecimal("302.0"), EXP, Type.PUT, BigDecimal.ONE, 5);

        assertTrue(puts.stream().noneMatch(c -> c.moneyness() == Moneyness.ITM), "no ITM puts");
        assertEquals(6, puts.size());   // 1 ATM + 5 OTM
        for (OptionContract c : puts) {
            // ATM at 302, OTM strictly below.
            assertTrue(c.strike().compareTo(new BigDecimal("302")) <= 0, "put strike at/below spot");
        }
        assertEquals(1, puts.stream().filter(c -> c.moneyness() == Moneyness.ATM).count());
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

}
