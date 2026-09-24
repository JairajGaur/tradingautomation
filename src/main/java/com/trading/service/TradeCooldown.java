package com.trading.service;

import com.trading.config.WebullProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-ticker trade cooldown: once a ticker is bought OR exited, it cannot be traded
 * again for {@code webull.strategies.re-trade-cooldown-minutes} (default 30). Prevents
 * immediate re-entry / churn on the same name after a fill or exit.
 */
@Service
public class TradeCooldown {

    private static final Logger log = LoggerFactory.getLogger(TradeCooldown.class);

    private final WebullProperties props;
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public TradeCooldown(WebullProperties props) {
        this.props = props;
    }

    /** Records trade activity (buy or exit) on a ticker, starting its cooldown. */
    public void record(String ticker) {
        if (ticker == null) return;
        lastActivity.put(ticker.toUpperCase(), Instant.now());
    }

    /** True when {@code ticker} may be traded (not within its cooldown window). */
    public boolean canTrade(String ticker) {
        int minutes = props.strategies().reTradeCooldownMinutes();
        if (minutes <= 0 || ticker == null) return true;
        Instant last = lastActivity.get(ticker.toUpperCase());
        if (last == null) return true;
        boolean cool = Instant.now().isAfter(last.plusSeconds(minutes * 60L));
        if (!cool) {
            log.debug("[TradeCooldown] {} in cooldown (last activity {}, window {} min)", ticker, last, minutes);
        }
        return cool;
    }

    /** Clears all cooldowns (daily reset). */
    public void clearAll() {
        lastActivity.clear();
    }
}
