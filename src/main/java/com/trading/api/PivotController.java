package com.trading.api;

import com.trading.service.StockPivotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Read-only standard daily pivots for any stock, independent of the option watchlist. */
@RestController
@RequestMapping("/api/pivots")
public class PivotController {
    private final StockPivotService pivots;
    public PivotController(StockPivotService pivots) { this.pivots=pivots; }
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String,Object>> get(@PathVariable String ticker) {
        String symbol=ticker.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z][A-Z0-9.\\-]{0,14}")) return ResponseEntity.badRequest().body(Map.of("error","Invalid stock symbol"));
        var result=pivots.current(symbol,Instant.now());
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("underlying",symbol);body.put("method","STANDARD");body.put("session","RTH 09:30–16:00 ET");
        body.put("pivotPoints",result.pivotPoints());body.put("stockContext",result.stockContext());
        body.put("informationalOnly",true);body.put("timestamp",TimeFormat.nowEt());
        return ResponseEntity.status(result.pivotPoints().available()?200:503).body(body);
    }
}
