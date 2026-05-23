package net.skywall.eventmaster;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Centralised environment + path configuration. Reads {@code .env} from the
 * working directory (when present) and falls back to {@link System#getenv}.
 *
 * <p>All output paths live under {@code run/} next to the working directory,
 * matching the Python implementation. {@code run/} is created on demand and
 * is git-ignored.
 */
public final class Config {

    public static final String DEFAULT_HERMES_WEBHOOK_URL =
            "http://127.0.0.1:8644/webhooks/luma-events";
    public static final String DEFAULT_HERMES_WEBHOOK_SECRET = "INSECURE_NO_AUTH";
    public static final String DEFAULT_GMAIL_LABEL = "miami-social-event-source";

    private static final Set<String> FALSY = Set.of("0", "false", "no");
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private final Dotenv dotenv;

    public final Path scriptDir;
    public final Path runDir;
    public final Path upcomingEventsPath;
    public final Path pastEventsPath;
    public final Path processedIdsPath;
    public final Path connectorStatePath;
    public final Path logPath;
    public final Path agentPromptPath;

    public Config() {
        this.dotenv = Dotenv.configure().ignoreIfMissing().load();

        this.scriptDir = Path.of("").toAbsolutePath();
        this.runDir = scriptDir.resolve("run");
        this.upcomingEventsPath = runDir.resolve("upcoming_events.json");
        this.pastEventsPath = runDir.resolve("past_events.json");
        this.processedIdsPath = runDir.resolve(".processed_ids");
        this.connectorStatePath = runDir.resolve(".connector-state.json");
        this.logPath = runDir.resolve("connector.log");

        String promptOverride = get("HERMES_AGENT_PROMPT_PATH");
        this.agentPromptPath = (promptOverride != null && !promptOverride.isBlank())
                ? Path.of(promptOverride).toAbsolutePath()
                : scriptDir.resolve("hermes").resolve("agent-prompt.txt");
    }

    public String gmailUser() {
        return requireNonBlank("GMAIL_USER");
    }

    public String gmailAppPassword() {
        return requireNonBlank("GMAIL_APP_PASSWORD");
    }

    public String gmailLabel() {
        String label = get("GMAIL_LABEL");
        return (label == null || label.isBlank()) ? DEFAULT_GMAIL_LABEL : label;
    }

    public List<String> lumaCalendars() {
        String raw = get("LUMA_CALENDARS");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public HermesWebhookConfig hermesWebhook() {
        boolean enabled = !FALSY.contains(orDefault(get("HERMES_WEBHOOK_ENABLED"), "true").strip().toLowerCase());
        String url = orDefault(get("HERMES_WEBHOOK_URL"), "").strip();
        String secret = orDefault(get("HERMES_WEBHOOK_SECRET"), "").strip();

        if (enabled) {
            if (url.isEmpty()) {
                url = DEFAULT_HERMES_WEBHOOK_URL;
                log.info("HERMES_WEBHOOK_URL unset — using default {}", url);
            }
            if (secret.isEmpty()) {
                secret = DEFAULT_HERMES_WEBHOOK_SECRET;
                log.info("HERMES_WEBHOOK_SECRET unset — using default {} (loopback gateway only)", secret);
            }
        }

        boolean noAuth = secret.isEmpty() || secret.equals(DEFAULT_HERMES_WEBHOOK_SECRET);
        return new HermesWebhookConfig(url, secret, enabled, noAuth);
    }

    private String get(String key) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.isEmpty()) {
            return sysEnv;
        }
        return dotenv.get(key);
    }

    private String requireNonBlank(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null) ? fallback : value;
    }

    public record HermesWebhookConfig(String url, String secret, boolean enabled, boolean noAuth) {}
}
