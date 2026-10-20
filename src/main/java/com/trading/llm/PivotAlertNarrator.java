package com.trading.llm;

import com.trading.config.AppProperties;
import com.trading.option.OptionContract;
import com.trading.option.OptionPivotFilter;
import com.trading.service.StockPivotService;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Optional factual wording only. No LLM result can affect eligibility, grouping or cooldown. */
@Service
public class PivotAlertNarrator {
    private final AppProperties props;
    private final LlmClient client;
    private final PromptTemplates prompts;

    public PivotAlertNarrator(AppProperties props, LlmClient client, PromptTemplates prompts) {
        this.props=props;this.client=client;this.prompts=prompts;
    }

    /** Empty means omit the entire pivot section. No raw-value or deterministic-message fallback. */
    public String summarize(String symbol, OptionContract.Type side, StockPivotService.Result context,
                            OptionPivotFilter.Decision decision) {
        if (props.llm()==null || !props.llm().enabled()) return "";
        try {
            List<String> choices=wordings(side,context,decision);
            if (choices.isEmpty()) return "";
            var mapper=client.mapper();
            var schema=mapper.createObjectNode();schema.put("type","object");schema.put("additionalProperties",false);
            var summary=schema.putObject("properties").putObject("summary");summary.put("type","string");
            var allowed=summary.putArray("enum");choices.forEach(allowed::add);
            schema.putArray("required").add("summary");
            String prompt=prompts.render("option-pivot-message", Map.of("symbol",symbol,"side",side.name(),
                    "wordings",mapper.writeValueAsString(choices)));
            // Reuse the configured integration/model, with a small total-call budget for optional wording.
            String raw=client.generateSchema(prompt,schema,null,2);
            var node=mapper.readTree(raw);
            if (node==null || !node.isObject() || node.size()!=1 || !node.path("summary").isTextual()) return "";
            String text=node.path("summary").asText();
            // Validate even if a runtime ignores JSON schema: invented claims/numbers are never delivered.
            return choices.contains(text) ? "\nPivot view: " + text : "";
        } catch (Exception ex) {
            LoggerFactory.getLogger(PivotAlertNarrator.class).debug("Pivot wording omitted for {}: {}",symbol,ex.getMessage());
            return "";
        }
    }

    static List<String> wordings(OptionContract.Type side, StockPivotService.Result context,
                                 OptionPivotFilter.Decision decision) {
        if (side==null || context==null || context.pivotPoints()==null || !context.pivotPoints().available()
                || context.stockContext()==null) return List.of();
        if (decision!=null && decision.enabled() && !decision.passes()) return List.of();
        var levels=context.pivotPoints();
        BigDecimal price=decimal(context.stockContext().get("stockPrice"));
        if (price==null || levels.pivot()==null) return List.of();
        int comparison=price.compareTo(levels.pivot());
        String location=comparison>0 ? "above" : comparison<0 ? "below" : "at";
        String bias=comparison>0 ? "bullish" : comparison<0 ? "bearish" : "neutral";
        String alignment=comparison==0 ? "neutral for" : ((comparison>0)==(side==OptionContract.Type.CALL) ? "supports" : "opposes");
        String direction=side==OptionContract.Type.CALL ? "resistance" : "support";
        String boundKey=side==OptionContract.Type.CALL ? "nearestUpperLevel" : "nearestLowerLevel";
        String priceKey=side==OptionContract.Type.CALL ? "upperLevelPrice" : "lowerLevelPrice";
        Object name=context.stockContext().get(boundKey);
        BigDecimal boundary=decimal(context.stockContext().get(priceKey));
        String room;
        if (name==null || boundary==null) {
            BigDecimal outer=side==OptionContract.Type.CALL ? levels.r2() : levels.s2();
            if (outer==null || (side==OptionContract.Type.CALL ? price.compareTo(outer)<=0 : price.compareTo(outer)>=0)) return List.of();
            room="Stock is already " + (side==OptionContract.Type.CALL ? "above R2" : "below S2")
                    + "; no further daily pivot " + direction + " is defined. This does not guarantee continuation.";
        } else {
            if (!List.of("P","R1","R2","S1","S2").contains(name.toString())) return List.of();
            BigDecimal distance=boundary.subtract(price).abs();
            room="Next " + direction + " is $"+money(boundary)+" ("+name+"), $"+money(distance)+" away";
            if (decision!=null && decision.enabled() && decision.roomAtr()!=null) {
                room+=" ("+decision.roomAtr().setScale(2,RoundingMode.HALF_UP).toPlainString()+"x stock ATR); pivot clearance passed";
            }
            room+=".";
        }
        return List.of("Stock is " + location + " the daily pivot; " + bias + " pivot bias " + alignment
                        + " this " + side.name() + ". " + room,
                "Daily pivot context: stock is " + location + " the pivot ("+bias+" bias, "+alignment+" this "+side.name()+"). " + room);
    }
    private static BigDecimal decimal(Object value) { return value==null ? null : new BigDecimal(value.toString()); }
    private static String money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP).toPlainString(); }
}
