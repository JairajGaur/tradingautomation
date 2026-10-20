package com.trading.option;

import com.trading.config.AppProperties;
import com.trading.model.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OptionContractRankingTest {
    private final AppProperties.Alerts.ContractRankingCfg cfg=new AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ONE);
    private OptionContractRanking.Candidate c(String name,String strike,Long volume) {
        return new OptionContractRanking.Candidate(name,new BigDecimal(strike),volume);
    }
    @Test void nearbyBandExcludesFarHighVolumeContractAndChoosesVolumeWithinBand() {
        var result=OptionContractRanking.select(List.of(c("ATM","100",100L),c("near","102.5",200L),c("far","105",100000L)),new BigDecimal("100"),new BigDecimal("2.5"),cfg);
        assertEquals("near",result.occSymbol());assertEquals(200,result.signalCandleVolume());
        assertEquals(new BigDecimal("2.5"),result.strikeDistance());assertEquals(2,result.nearbyContracts());assertEquals(3,result.qualifyingContracts());
    }
    @Test void volumeTiesFavorDistanceThenStrikeAndStableSymbolOrder() {
        var inputs=List.of(c("far","102.5",100L),c("close","100",100L));
        assertEquals("close",OptionContractRanking.select(inputs,new BigDecimal("101"),new BigDecimal("2.5"),cfg).occSymbol());
        inputs=List.of(c("upper","101",100L),c("lowerZ","99",100L),c("lowerA","99",100L));
        assertEquals("lowerA",OptionContractRanking.select(inputs,new BigDecimal("100"),BigDecimal.ONE,cfg).occSymbol());
    }
    @Test void fractionalStrikeGapAndNonAtmClosestQualifyingStrikeAreSupported() {
        var result=OptionContractRanking.select(List.of(c("a","10",100L),c("b","10.5",200L),c("far","11",10000L)),new BigDecimal("10.2"),new BigDecimal("0.5"),cfg);
        assertEquals("b",result.occSymbol());
        result=OptionContractRanking.select(List.of(c("near","110",100L),c("next","112.5",200L),c("far","115",100000L)),new BigDecimal("100"),new BigDecimal("2.5"),cfg);
        assertEquals("next",result.occSymbol());assertEquals(new BigDecimal("12.5"),result.distanceLimit());
    }
    @Test void missingVolumeIsNotReplacedBySessionVolumeAndMissingReferenceDoesNotSelect() {
        var inputs=List.of(c("unknown","100",null),c("far","105",100000L));
        assertNull(OptionContractRanking.select(inputs,new BigDecimal("100"),new BigDecimal("2.5"),cfg));
        assertNull(OptionContractRanking.select(inputs,null,BigDecimal.ONE,cfg));
        assertNull(OptionContractRanking.select(inputs,new BigDecimal("100"),BigDecimal.ZERO,cfg));
    }
    @Test void zeroCandleVolumeIsKnownDataAndZeroGapAllowanceSelectsClosest() {
        var zero=new AppProperties.Alerts.ContractRankingCfg(true,BigDecimal.ZERO);
        assertEquals("closest",OptionContractRanking.select(List.of(c("closest","100",0L),c("farther","102.5",999L)),new BigDecimal("100"),new BigDecimal("2.5"),zero).occSymbol());
    }
    @Test void stockReferenceUsesCompletedHistoricalPriceAndRejectsStaleData() {
        var start=Instant.parse("2026-10-19T13:30:00Z");
        var price=new BigDecimal("100");var future=new BigDecimal("9999");
        var bars=List.of(new Candle("AAPL",start,price,price,price,price,100),new Candle("AAPL",start.plusSeconds(60),future,future,future,future,100));
        assertEquals(price,OptionContractRanking.stockPriceAt(bars,start.plusSeconds(60),null));
        assertNull(OptionContractRanking.stockPriceAt(bars,start.plusSeconds(1200),null));
    }
    @Test void rankingConfigurationBindsAndDefaultsToOffIfOmitted() {
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "enabled","true","contract-ranking.enabled","true","contract-ranking.nearby-strike-gaps","1"));
        var config=new org.springframework.boot.context.properties.bind.Binder(source).bind("",org.springframework.boot.context.properties.bind.Bindable.of(AppProperties.Alerts.OptionVolStCfg.class)).get();
        assertTrue(config.contractRanking().enabled());assertEquals(BigDecimal.ONE,config.contractRanking().nearbyStrikeGaps());
    }
}
