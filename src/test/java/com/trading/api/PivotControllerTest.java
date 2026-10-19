package com.trading.api;

import com.trading.service.StockPivotService;
import com.trading.indicator.DailyPivotCalculator;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PivotControllerTest {
    @Test void anyTickerUsesReadOnlyServiceAndReturnsExplicitUnavailableStatus() {
        var service=mock(StockPivotService.class);
        when(service.current(eq("MSFT"),any())).thenReturn(new StockPivotService.Result(
                DailyPivotCalculator.unavailable("MSFT",LocalDate.of(2026,10,19),null,"Missing prior session"),Map.of()));
        var controller=new PivotController(service);
        var response=controller.get("msft");assertEquals(503,response.getStatusCode().value());
        assertEquals(true,response.getBody().get("informationalOnly"));
        verify(service).current(eq("MSFT"),any());
    }
    @Test void invalidSymbolDoesNotFetchData() {
        var service=mock(StockPivotService.class);
        assertEquals(400,new PivotController(service).get("BAD!").getStatusCode().value());verifyNoInteractions(service);
    }
}
