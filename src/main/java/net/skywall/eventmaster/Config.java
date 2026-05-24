package net.skywall.eventmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Centralised configuration. Reads {@code eventmaster.properties} from the
 * application directory (the folder containing the JAR) and falls back to
 * {@link System#getenv}.
 */
public final class Config {

    private static final String PROPERTIES_FILE = "eventmaster.properties";

    public static final String DEFAULT_HERMES_WEBHOOK_URL =
            "http://127.0.0.1:8644/webhooks/eventmaster";
    public static final String DEFAULT_HERMES_API_URL = "http://127.0.0.1:8642";
    public static final String DEFAULT_HERMES_API_MODEL = "hermes-agent";
    public static final String DEFAULT_HERMES_WEBHOOK_SECRET = "INSECURE_NO_AUTH";
    public static final String DEFAULT_GMAIL_LABEL = "miami-social-event-source";

    private static final Set<String> FALSY = Set.of("0", "false", "no");
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private final Properties properties;

    public final Path root;
    public final Path upcomingEventsPath;
    public final Path pastEventsPath;
    public final Path processedIdsPath;
    public final Path connectorStatePath;
    public final Path logPath;
    public final Path agentPromptPath;
    public final Path instagramClassifierPromptPath;

    public Config() {
        this.root = AppRoot.directory();
        this.properties = loadProperties();

        this.upcomingEventsPath = root.resolve("upcoming_events.json");
        this.pastEventsPath = root.resolve("past_events.json");
        this.processedIdsPath = root.resolve("processed_ids.txt");
        this.connectorStatePath = root.resolve("connector-state.json");
        this.logPath = resolveLogPath();

        String promptOverride = get("HERMES_AGENT_PROMPT_PATH");
        this.agentPromptPath = (promptOverride != null && !promptOverride.isBlank())
                ? Path.of(promptOverride).toAbsolutePath()
                : root.resolve("hermes").resolve("agent-prompt.txt");

        String classifierPromptOverride = get("HERMES_INSTAGRAM_CLASSIFIER_PROMPT_PATH");
        this.instagramClassifierPromptPath = (classifierPromptOverride != null && !classifierPromptOverride.isBlank())
                ? Path.of(classifierPromptOverride).toAbsolutePath()
                : root.resolve("hermes").resolve("instagram-classifier-prompt.txt");
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

    public List<String> instagramAccounts() {
        String raw = get("INSTAGRAM_ACCOUNTS");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ScrapeCreatorsClient::normalizeHandle)
                .toList();
    }

    public boolean instagramEnabled() {
        return !instagramAccounts().isEmpty();
    }

    public String scrapeCreatorsApiKey() {
        if (!instagramEnabled()) {
            return null;
        }
        return requireNonBlank("SCRAPECREATORS_API_KEY");
    }

    public HermesApiConfig hermesApi() {
        boolean enabled = !FALSY.contains(orDefault(get("HERMES_API_ENABLED"), "true").strip().toLowerCase());
        String baseUrl = orDefault(get("HERMES_API_URL"), DEFAULT_HERMES_API_URL).strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String apiKey = orDefault(get("HERMES_API_KEY"), "").strip();
        String model = orDefault(get("HERMES_API_MODEL"), DEFAULT_HERMES_API_MODEL).strip();
        return new HermesApiConfig(baseUrl, apiKey.isEmpty() ? null : apiKey, model, enabled);
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

    private Path resolveLogPath() {
        String configured = System.getProperty("eventmaster.log.path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String override = get("EVENTMASTER_LOG_PATH");
        if (override != null && !override.isBlank()) {
            return AppRoot.resolve(override);
        }
        return root.resolve("connector.log");
    }

    private Properties loadProperties() {
        Properties props = new Properties();
        Path propsPath = root.resolve(PROPERTIES_FILE);
        if (!Files.isRegularFile(propsPath)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(propsPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + propsPath.toAbsolutePath(), e);
        }
        return props;
    }

    private String get(String key) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.isEmpty()) {
            return sysEnv;
        }
        return properties.getProperty(key);
    }

    private String requireNonBlank(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null) ? fallback : value;
    }

    public record HermesWebhookConfig(String url, String secret, boolean enabled, boolean noAuth) {}

    public record HermesApiConfig(String baseUrl, String apiKey, String model, boolean enabled) {}
}
