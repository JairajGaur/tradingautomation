package com.trading.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.AppProperties;
import com.trading.indicator.DailyPivotCalculator;
import com.trading.option.OptionContract;
import com.trading.option.OptionPivotFilter;
import com.trading.service.StockPivotService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PivotAlertNarratorTest {
    private final AppProperties props=mock(AppProperties.class,RETURNS_DEEP_STUBS);
    private final LlmClient client=mock(LlmClient.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private PivotAlertNarrator narrator() {
        when(props.llm().enabled()).thenReturn(true);
        when(client.mapper()).thenReturn(mapper);
        return new PivotAlertNarrator(props,client,new PromptTemplates());
    }
    private StockPivotService.Result context(String price, boolean aboveOuter) {
        var levels=new DailyPivotCalculator.Levels("AAPL",LocalDate.of(2026,10,19),LocalDate.of(2026,10,16),true,
                new BigDecimal("110"),new BigDecimal("90"),new BigDecimal("100"),new BigDecimal("100"),
                new BigDecimal("110"),new BigDecimal("90"),new BigDecimal("120"),new BigDecimal("80"),"STANDARD");
        Map<String,Object> stock=aboveOuter ? Map.of("stockPrice",new BigDecimal(price))
                : Map.of("stockPrice",new BigDecimal(price),"nearestUpperLevel","R1","upperLevelPrice",new BigDecimal("110"),
                    "nearestLowerLevel","P","lowerLevelPrice",new BigDecimal("100"));
        return new StockPivotService.Result(levels,stock);
    }
    private void validModel() {
        when(client.generateSchema(anyString(),any(JsonNode.class),isNull(),eq(2))).thenAnswer(inv -> {
            JsonNode schema=inv.getArgument(1);
            String text=schema.path("properties").path("summary").path("enum").get(0).asText();
            return mapper.writeValueAsString(Map.of("summary",text));
        });
    }
    @Test void configuredLlmProducesRelevantHumanContextWithoutTheLevelDump() {
        var narrator=narrator();validModel();
        var decision=new OptionPivotFilter.Decision(true,true,"PIVOT_ROOM_OK","ABOVE_P",new BigDecimal("105"),
                BigDecimal.ONE,"R1",new BigDecimal("110"),new BigDecimal("5"),new BigDecimal("5"),LocalDate.of(2026,10,16));
        String text=narrator.summarize("AAPL",OptionContract.Type.CALL,context("105",false),decision);
        assertTrue(text.contains("bullish pivot bias supports this CALL"));
        assertTrue(text.contains("Next resistance is $110.00 (R1), $5.00 away"));
        assertTrue(text.contains("5.00x stock ATR"));
        assertFalse(text.contains("S1"));assertFalse(text.contains("S2"));assertFalse(text.contains("R2"));
        verify(client).generateSchema(contains("AAPL CALL"),any(JsonNode.class),isNull(),eq(2));
    }
    @Test void disabledLlmAndMissingStockContextOmitAllPivotDetails() {
        var narrator=narrator();when(props.llm().enabled()).thenReturn(false);
        assertEquals("",narrator.summarize("AAPL",OptionContract.Type.CALL,context("105",false),null));
        verify(client,never()).generateSchema(anyString(),any(),any(),anyInt());
        when(props.llm().enabled()).thenReturn(true);
        assertEquals("",narrator.summarize("AAPL",OptionContract.Type.CALL,null,null));
        verify(client,never()).generateSchema(anyString(),any(),any(),anyInt());
    }
    @Test void timeoutMalformedOrUnsupportedModelOutputOmitInsteadOfFallback() {
        var narrator=narrator();
        when(client.generateSchema(anyString(),any(),isNull(),eq(2))).thenThrow(new RuntimeException("timeout"));
        assertEquals("",narrator.summarize("AAPL",OptionContract.Type.CALL,context("105",false),null));
        reset(client);when(client.mapper()).thenReturn(mapper);
        when(client.generateSchema(anyString(),any(),isNull(),eq(2))).thenReturn("invalid", "{\"summary\":\"Guaranteed breakout to $999\"}");
        assertEquals("",narrator.summarize("AAPL",OptionContract.Type.CALL,context("105",false),null));
        assertEquals("",narrator.summarize("AAPL",OptionContract.Type.CALL,context("105",false),null));
    }
    @Test void putAbovePivotExplainsOpposingBiasAndOnlyRelevantSupport() {
        var narrator=narrator();validModel();
        String text=narrator.summarize("AAPL",OptionContract.Type.PUT,context("105",false),null);
        assertTrue(text.contains("bullish pivot bias opposes this PUT"));
        assertTrue(text.contains("Next support is $100.00 (P), $5.00 away"));
        assertFalse(text.contains("clearance passed"));
    }
    @Test void beyondOuterPivotDoesNotInventTargetOrGuaranteeContinuation() {
        var narrator=narrator();validModel();
        String text=narrator.summarize("AAPL",OptionContract.Type.CALL,context("121",true),null);
        assertTrue(text.contains("above R2"));assertTrue(text.contains("no further daily pivot resistance is defined"));
        assertTrue(text.contains("does not guarantee continuation"));assertFalse(text.contains("$"));
    }
}
