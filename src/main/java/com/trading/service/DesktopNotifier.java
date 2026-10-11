package com.trading.service;

import com.trading.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fires native <b>macOS desktop banners</b> for fired alerts by shelling out to
 * {@code osascript -e 'display notification ...'}. Parallel to {@link TelegramChannelSender}
 * (a delivery channel), and like it, <b>fail-safe</b>: any error is logged and swallowed so a
 * notification problem can never disrupt scanning or trading.
 *
 * <p><b>Burst coalescing.</b> When many alerts fire in the same scan tick, they are collapsed
 * into a SINGLE banner — one line per alert in the banner body ({@code <type> <TICKER> <emoji>
 * <Buy|Sell>}) — rather than one banner each. Banners stack in Notification Center and don't
 * steal focus; individual alerts are still delivered to Telegram and logged separately.</p>
 *
 * <p>Gated by {@code app.notifications.desktop-enabled} (default false). No-op when disabled, not
 * on macOS, or {@code osascript} is unavailable. Only works on the Mac's active GUI session.</p>
 */
@Service
public class DesktopNotifier {

    private static final Logger log = LoggerFactory.getLogger(DesktopNotifier.class);
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final AppProperties props;

    public DesktopNotifier(AppProperties props) {
        this.props = props;
    }

    /** One fired alert for a desktop banner. */
    public record Fired(String alertType, String ticker, String direction, String timeframe) {}

    /** Whether desktop notifications are enabled AND possible on this host. */
    public boolean isEnabled() {
        AppProperties.Notifications n = props.notifications();
        return IS_MAC && n != null && n.desktopEnabled();
    }

    /** Convenience for a single fired alert. */
    public void notify(String alertType, String ticker, String direction, String timeframe) {
        notifyBatch(List.of(new Fired(alertType, ticker, direction, timeframe)));
    }

    /**
     * Emits ONE banner for a burst of fired alerts: each alert on its OWN line in the body
     * ({@code <type> <TICKER> <emoji> <Buy|Sell>}). A single alert is just a one-line banner;
     * the title shows the count when more than one fired.
     * No-op when disabled / non-mac / empty. Never throws.
     */
    public void notifyBatch(List<Fired> fired) {
        if (!isEnabled() || fired == null || fired.isEmpty()) return;
        try {
            String title = fired.size() == 1 ? "Trading Alert" : "Trading Alerts (" + fired.size() + ")";
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < fired.size(); i++) {
                if (i > 0) body.append('\n');   // each alert on its own line
                body.append(line(fired.get(i)));
            }
            fireBanner(title, body.toString());
        } catch (Exception e) {
            log.debug("[DesktopNotifier] notifyBatch failed (ignored): {}", e.getMessage());
        }
    }

    /** "<timeframe> <TICKER> <emoji> <BUY|SELL> -- <alert-type>" e.g. "M5 AAPL 🟢 BUY -- ORB". */
    private static String line(Fired f) {
        String emoji = emoji(f.direction()), action = action(f.direction());
        StringBuilder b = new StringBuilder();
        if (!safe(f.timeframe()).isBlank()) b.append(f.timeframe()).append(' ');
        b.append(f.ticker());
        if (!emoji.isEmpty()) b.append(' ').append(emoji);
        if (!action.isEmpty()) b.append(' ').append(action);
        String type = typeLabel(f.alertType());
        if (!type.isBlank()) b.append(" -- ").append(type);
        return b.toString();
    }

    /** Normalises the raw alert id to a short label: orb-up/orb-down → "ORB", swift → "Swift", etc. */
    private static String typeLabel(String alertId) {
        String id = safe(alertId).trim();
        if (id.isEmpty()) return "";
        String low = id.toLowerCase();
        if (low.startsWith("orb")) return "ORB";
        if (low.startsWith("swift")) return "Swift";
        if (low.startsWith("st-adx") || low.startsWith("stadx")) return "ST+ADX";
        return id;   // unknown — show as given
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String emoji(String dir) {
        return "UP".equalsIgnoreCase(dir) ? "\uD83D\uDFE2" : "DOWN".equalsIgnoreCase(dir) ? "\uD83D\uDD34" : "";
    }
    private static String action(String dir) {
        return "UP".equalsIgnoreCase(dir) ? "BUY" : "DOWN".equalsIgnoreCase(dir) ? "SELL" : "";
    }

    /** Fires the osascript banner, bounded and fail-safe. */
    private void fireBanner(String title, String body) throws Exception {
        String sound = props.notifications().desktopSound();
        StringBuilder script = new StringBuilder("display notification \"")
                .append(esc(body)).append("\" with title \"").append(esc(title)).append("\"");
        // The banner's own "sound name" only plays if macOS notification-sound is enabled for
        // this app. We ALSO play it via afplay (below) so there is always an audible cue.
        if (sound != null && !sound.isBlank()) {
            script.append(" sound name \"").append(esc(sound)).append("\"");
        }
        Process p = new ProcessBuilder("osascript", "-e", script.toString())
                .redirectErrorStream(true).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        playSound(sound);
    }

    /**
     * Plays the system sound directly via {@code afplay} — independent of macOS notification-sound
     * settings, so an alert is always AUDIBLE even if notification sound is muted for this app.
     * Fire-and-forget and fail-safe. No-op when the sound is blank or the file doesn't exist.
     */
    private void playSound(String sound) {
        if (sound == null || sound.isBlank()) return;
        try {
            java.io.File f = new java.io.File("/System/Library/Sounds/" + sound + ".aiff");
            if (!f.isFile()) return;   // unknown sound name — skip quietly
            new ProcessBuilder("afplay", f.getAbsolutePath()).start();   // don't wait
        } catch (Exception e) {
            log.debug("[DesktopNotifier] afplay failed (ignored): {}", e.getMessage());
        }
    }

    /** Escapes backslashes and double-quotes for safe embedding in an AppleScript string literal. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
