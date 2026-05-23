package net.skywall.eventmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Hermes OpenAI-compatible API server ({@code POST /v1/chat/completions}).
 */
public final class HermesCompletionsClient {

    private static final Logger log = LoggerFactory.getLogger(HermesCompletionsClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final Config.HermesApiConfig api;

    public HermesCompletionsClient(Config config) {
        this.api = config.hermesApi();
    }

    public Config.HermesApiConfig api() {
        return api;
    }

    /**
     * Run a stateless chat completion. Returns the assistant message text.
     *
     * @throws IOException on network, HTTP, or malformed response errors
     */
    public String complete(String systemPrompt, String userPrompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", api.model());
        body.put("stream", false);
        body.put("tool_choice", "none");
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(api.baseUrl() + "/v1/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.COMPACT.writeValueAsBytes(body)));

        if (api.apiKey() != null && !api.apiKey().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + api.apiKey());
        }

        HttpResponse<String> response;
        try {
            response = Http.CLIENT.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Hermes API request interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            String responseBody = response.body() == null ? "" : response.body();
            throw new IOException("Hermes API rejected request ("
                    + response.statusCode() + "): "
                    + (responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody));
        }

        JsonNode root = Json.MAPPER.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IOException("Hermes API response missing choices[0].message.content");
        }

        String text = content.asString(null);
        if (text == null || text.isBlank()) {
            throw new IOException("Hermes API returned empty completion");
        }

        log.debug("Hermes completion received ({} chars)", text.length());
        return text.strip();
    }
}
