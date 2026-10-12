package com.trading.llm;

import com.trading.indicator.SupertrendCalculator;
import com.trading.indicator.SupertrendService;
import com.trading.model.Candle;
import com.trading.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link MarketBriefingBuilder} distils a candle + Supertrend series into the
 * expected facts. {@link MarketDataService} is mocked to return a hand-built series; the
 * Supertrend math is the REAL {@link SupertrendCalculator} (length 3, factor 1) wired
 * through a stubbed {@link SupertrendService}, so the ATR/flip/separation facts are
 * computed, not faked.
 */
class MarketBriefingBuilderTest {

    private static final int LEN = 3;
    private static final BigDecimal FACTOR = BigDecimal.ONE;

    private static Candle bar(int i, double high, double low, double close, long volume) {
        return new Candle("TEST", Instant.ofEpochSecond(i * 60L),
                BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), volume);
    }

    private MarketBriefingBuilder builderWith(List<Candle> candles) {
        MarketDataService md = mock(MarketDataService.class);
        when(md.fetchHistoricalBars(anyString(), anyInt(), anyString(), any()))
                .thenReturn(candles);

        // Stub SupertrendService to use the REAL calculator with test params.
        SupertrendService st = mock(SupertrendService.class);
        when(st.length()).thenReturn(LEN);
        when(st.factor()).thenReturn(FACTOR);
        when(st.calculate(any())).thenAnswer(inv ->
                SupertrendCalculator.calculate(inv.getArgument(0), LEN, FACTOR));

        return new MarketBriefingBuilder(md, st);
    }

    @Test
    void distilsSteadyUptrendIntoFacts() {
        // Rising series → steady UP trend, no flips, line below price.
        List<Candle> candles = new ArrayList<>();
        candles.add(bar(0, 10, 8, 9, 100));
        candles.add(bar(1, 11, 9, 10, 120));
        candles.add(bar(2, 12, 10, 11, 140));
        candles.add(bar(3, 13, 11, 12, 160));
        candles.add(bar(4, 14, 12, 13, 180));
        candles.add(bar(5, 15, 13, 14, 200));

        MarketBriefingBuilder.Result r = builderWith(candles)
                .build("TEST", 6, "M5", null, "RTH");

        List<String> facts = r.briefing().facts();
        String joined = String.join("\n", facts);

        // Core facts present.
        assertTrue(joined.contains("Window: 6 M5 bars for TEST."), joined);
        assertTrue(joined.contains("Current price (last close): 14"), joined);
        // The rising series seeds DOWN then flips UP on the final bar (real ST
        // behaviour at length 3 / factor 1): latest direction is UP, with exactly
        // one flip, 0 bars ago.
        assertTrue(joined.contains("is UP"), "expected UP Supertrend\n" + joined);
        assertTrue(joined.contains("flipped 1 time(s)"), "expected 1 flip\n" + joined);
        assertTrue(joined.contains("flip was 0 bar(s) ago, to UP"),
                "expected a fresh UP flip\n" + joined);
        // Price-vs-Supertrend separation is reported in ATR units.
        assertTrue(joined.contains("ATR above (support)"), "expected ATR separation\n" + joined);
        // Rising volume (recent half avg > older half).
        assertTrue(joined.contains("Average volume is rising"), joined);
        // Recent-bar tail is present.
        assertTrue(joined.contains("Recent bars"), joined);
        // Briefing metadata carried through.
        assertEquals("TEST", r.briefing().ticker());
        assertEquals("M5", r.briefing().timeframe());
        assertEquals("RTH", r.briefing().session());
    }

    @Test
    void throwsWhenNoData() {
        MarketBriefingBuilder b = builderWith(List.of());
        assertThrows(IllegalStateException.class,
                () -> b.build("TEST", 6, "M5", null, "RTH"));
    }

    @Test
    void throwsWhenNotEnoughBarsForSupertrend() {
        // Only 2 bars but Supertrend length is 3 → needs length+1.
        List<Candle> candles = new ArrayList<>();
        candles.add(bar(0, 10, 8, 9, 100));
        candles.add(bar(1, 11, 9, 10, 120));

        MarketBriefingBuilder b = builderWith(candles);
        assertThrows(IllegalStateException.class,
                () -> b.build("TEST", 6, "M5", null, "RTH"));
    }
}
