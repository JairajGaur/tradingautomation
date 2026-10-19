package com.trading.option;

import com.trading.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionUniverseLoaderTest {
    private OptionUniverseLoader load(String text) {
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.engine().optionTickersPath()).thenReturn("test:options");
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("test:options"))
                .thenReturn(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        return new OptionUniverseLoader(props, resources);
    }

    @Test
    void groupedSymbolsShareGapAndDefaultResetsIt() {
        var loader = load("[strike-gap=2.5]\nAAPL\nTSLA\nNVDA\n[default]\nSPY\nQQQ\n");
        assertEquals(List.of("AAPL", "TSLA", "NVDA", "SPY", "QQQ"), loader.getUnderlyings());
        for (String symbol : List.of("AAPL", "TSLA", "NVDA")) {
            assertEquals(new BigDecimal("2.5"), loader.strikeIncrementFor(symbol));
        }
        assertNull(loader.strikeIncrementFor("SPY"));
        assertNull(loader.strikeIncrementFor("QQQ"));
    }

    @Test
    void legacyFormatAndBlankGapStillWork() {
        var loader = load("AAPL,2.5\nSPY,1.0\nQQQ,\nIWM\n");
        assertEquals(new BigDecimal("2.5"), loader.strikeIncrementFor("AAPL"));
        assertEquals(new BigDecimal("1.0"), loader.strikeIncrementFor("SPY"));
        assertNull(loader.strikeIncrementFor("QQQ"));
        assertNull(loader.strikeIncrementFor("IWM"));
    }

    @Test
    void inlineCommentsOverridesAndDuplicateSymbolsAreHandled() {
        var loader = load("# options\n[strike-gap=2.5] # shared\n aapl # comment\n"
                + "TSLA,5\n[default]\nAAPL,1\nAMZN\namzn,2.5\n");
        assertEquals(List.of("AAPL", "TSLA", "AMZN"), loader.getUnderlyings());
        assertEquals(new BigDecimal("2.5"), loader.strikeIncrementFor("AAPL"));
        assertEquals(new BigDecimal("5"), loader.strikeIncrementFor("TSLA"));
        assertNull(loader.strikeIncrementFor("AMZN"));
    }

    @Test
    void malformedSectionsDoNotBecomeTickersOrReusePreviousGap() {
        var loader = load("[strike-gap=2.5]\nAAPL\n[strike-gap=0]\nBAD\n"
                + "[strike-gap=nope]\nBAD2\n[unknown]\nBAD3\n[default]\nSPY\n");
        assertEquals(List.of("AAPL", "SPY"), loader.getUnderlyings());
    }

    @Test
    void malformedOverridesFallBackToSectionOrDefault() {
        var loader = load("AAPL,nope\n[strike-gap=2.5]\nTSLA,-1\nNVDA,\n");
        assertNull(loader.strikeIncrementFor("AAPL"));
        assertEquals(new BigDecimal("2.5"), loader.strikeIncrementFor("TSLA"));
        assertEquals(new BigDecimal("2.5"), loader.strikeIncrementFor("NVDA"));
    }
}
