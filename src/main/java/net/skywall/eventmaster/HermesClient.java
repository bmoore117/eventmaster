package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.Health;
import net.skywall.eventmaster.model.WebhookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds, signs, and POSTs the Hermes webhook payload. Mirrors
 * {@code build_hermes_webhook_payload}, {@code _hermes_webhook_signature},
 * {@code _post_hermes_payload}, and {@code notify_hermes} from
 * {@code fetch_events.py}.
 */
public final class HermesClient {

    private static final Logger log = LoggerFactory.getLogger(HermesClient.class);
    private static final Duration POST_TIMEOUT = Duration.ofSeconds(30);

    private final Config config;
    private final Config.HermesWebhookConfig webhook;

    public HermesClient(Config config) {
        this.config = config;
        this.webhook = config.hermesWebhook();
    }

    public Config.HermesWebhookConfig webhook() {
        return webhook;
    }

    /** Read the agent prompt file. Throws {@link IOException} on missing/unreadable file. */
    public String loadAgentPrompt() throws IOException {
        if (!Files.isRegularFile(config.agentPromptPath)) {
            throw new IOException("Agent prompt not found: " + config.agentPromptPath);
        }
        return Files.readString(config.agentPromptPath, StandardCharsets.UTF_8).strip();
    }

    public WebhookPayload buildPayload(String triggeredAt, List<Event> newEvents, Health health) throws IOException {
        return new WebhookPayload(
                loadAgentPrompt(),
                triggeredAt,
                health.hasErrors(),
                newEvents,
                health
        );
    }

    /** Compute the HMAC-SHA256 of {@code body} keyed by {@code secret}, hex-encoded. */
    public static String signature(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** POST the payload to the configured route. Returns true on a 2xx response. */
    public boolean post(WebhookPayload payload) {
        if (!webhook.enabled()) {
            log.error("Hermes webhook disabled (HERMES_WEBHOOK_ENABLED)");
            return false;
        }
        if (webhook.url() == null || webhook.url().isBlank()) {
            log.error("Hermes webhook not configured (set HERMES_WEBHOOK_URL)");
            return false;
        }

        byte[] body;
        body = Json.COMPACT.writeValueAsBytes(payload);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(webhook.url()))
                .timeout(POST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", payload.triggeredAt())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (!webhook.noAuth()) {
            reqBuilder.header("X-Webhook-Signature", signature(body, webhook.secret()));
        }

        HttpResponse<String> resp;
        try {
            resp = Http.CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("Hermes webhook request failed: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Hermes webhook request interrupted");
            return false;
        }

        if (resp.statusCode() / 100 == 2) {
            log.info("Hermes webhook accepted ({})", resp.statusCode());
            return true;
        }

        String bodyText = resp.body() == null ? "" : resp.body();
        log.error("Hermes webhook rejected ({}): {}",
                resp.statusCode(),
                bodyText.length() > 500 ? bodyText.substring(0, 500) : bodyText);
        return false;
    }

    /** Convenience: build + post. Logs the result. Returns true on a 2xx response. */
    public boolean notify(String triggeredAt, List<Event> newEvents, Health health) {
        WebhookPayload payload;
        try {
            payload = buildPayload(triggeredAt, newEvents, health);
        } catch (IOException e) {
            log.error("{}", e.getMessage());
            return false;
        }

        boolean ok = post(payload);
        if (ok) {
            log.info("Notified Hermes: {} new event(s), hasErrors={}",
                    newEvents.size(), payload.hasErrors());
        }
        return ok;
    }
}
