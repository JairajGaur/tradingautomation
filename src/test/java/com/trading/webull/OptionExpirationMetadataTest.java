package com.trading.webull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.BrokerResponse;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OptionExpirationMetadataTest {
    private WebullV3Client client() {
        var props = mock(WebullApiProperties.class, RETURNS_DEEP_STUBS);
        when(props.endpoints().optionContracts()).thenReturn("/contracts");
        return spy(new WebullV3Client(props));
    }
    private BrokerResponse page(String json) throws Exception {
        return new BrokerResponse(200,true,new ObjectMapper().readTree(json),json);
    }
    @Test void combinesAllUpcomingPagesWithoutAssumingBrokerSortOrder() throws Exception {
        var client = client();
        var date = LocalDate.now().plusDays(1);
        doReturn(page("{\"data\":[{\"underlying_symbol\":\"AAPL\",\"expiration_date\":\""+date.plusDays(7)+"\"}],\"pagination_key\":\"next\"}"))
                .when(client).get("/contracts",Map.of("category","US_OPTION","underlying_symbols","AAPL","status","LISTING","start_date",date.toString()));
        doReturn(page("{\"data\":[{\"underlying_symbol\":\"AAPL\",\"expiration_date\":\""+date+"\"}]}"))
                .when(client).get("/contracts",Map.of("category","US_OPTION","underlying_symbols","AAPL","status","LISTING","start_date",date.toString(),"pagination_key","next"));
        assertEquals(List.of(date,date.plusDays(7)),client.fetchOptionExpirations("AAPL",date));
    }
    @Test void exactSessionLookupRecoversExpiredContractsOmittedByGeneralListing() throws Exception {
        var client = client();
        var date = LocalDate.now(java.time.ZoneId.of("America/New_York")).minusDays(2);
        doReturn(page("{\"data\":[{\"underlying_symbol\":\"SPY\",\"expiration_date\":\""+date+"\",\"tradable_status\":\"NT\"}]}"))
                .when(client).get("/contracts",Map.of("category","US_OPTION","underlying_symbols","SPY","status","LISTING","end_date",date.toString()));
        assertEquals(List.of(date),client.fetchOptionExpirations("SPY",date));
        verify(client,times(1)).get(anyString(),anyMap());
    }
    @Test void checksInterveningDaysInOrderWhenLastSessionHadNoExpiry() throws Exception {
        var client = client();
        var today = LocalDate.now(java.time.ZoneId.of("America/New_York"));
        var date = today.minusDays(2);
        doReturn(page("{\"data\":[]}")).when(client).get(eq("/contracts"),anyMap());
        doReturn(page("{\"data\":[{\"underlying_symbol\":\"AAPL\",\"expiration_date\":\""+date.plusDays(1)+"\"}]}"))
                .when(client).get("/contracts",Map.of("category","US_OPTION","underlying_symbols","AAPL","status","LISTING","end_date",date.plusDays(1).toString()));
        assertEquals(List.of(date.plusDays(1)),client.fetchOptionExpirations("AAPL",date));
        verify(client,times(2)).get(anyString(),anyMap());
    }
    @Test void refusesIncompletePaginationAndBrokerFailures() throws Exception {
        var client = client();
        doReturn(page("{\"data\":[],\"pagination_key\":\"same\"}")).when(client).get(eq("/contracts"),anyMap());
        assertThrows(IllegalStateException.class,()->client.fetchOptionExpirations("AAPL",LocalDate.now().plusDays(1)));
        doReturn(new BrokerResponse(403,false,null,"denied")).when(client).get(eq("/contracts"),anyMap());
        assertThrows(IllegalStateException.class,()->client.fetchOptionExpirations("AAPL",LocalDate.now().minusDays(2)));
    }
    @Test void ignoresUnrelatedAdjustedAndNonTradableLiveContracts() throws Exception {
        var client = client();
        var date = LocalDate.now().plusDays(1);
        String json="{\"data\":[{\"underlying_symbol\":\"MSFT\",\"expiration_date\":\""+date+"\"},"
                +"{\"underlying_symbol\":\"AAPL\",\"root_symbol\":\"AAPL1\",\"expiration_date\":\""+date+"\"},"
                +"{\"underlying_symbol\":\"AAPL\",\"tradable_status\":\"NT\",\"expiration_date\":\""+date+"\"},"
                +"{\"underlying_symbol\":\"AAPL\",\"expiration_date\":\""+date.plusDays(7)+"\"}]}";
        doReturn(page(json)).when(client).get(eq("/contracts"),anyMap());
        assertEquals(List.of(date.plusDays(7)),client.fetchOptionExpirations("AAPL",date));
    }
}
