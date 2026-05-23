package net.skywall.eventmaster;

import net.skywall.eventmaster.model.InstagramPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches public Instagram posts via the ScrapeCreators API.
 */
public final class ScrapeCreatorsClient {

    private static final Logger log = LoggerFactory.getLogger(ScrapeCreatorsClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String BASE_URL = "https://api.scrapecreators.com";
    private static final String POSTS_PATH = "/v2/instagram/user/posts";

    private final String apiKey;

    public ScrapeCreatorsClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Fetch the latest page of posts for {@code handle}. Returns an empty list on failure. */
    public List<InstagramPost> fetchUserPosts(String handle) {
        String normalizedHandle = normalizeHandle(handle);
        String query = "handle=" + URLEncoder.encode(normalizedHandle, StandardCharsets.UTF_8) + "&trim=true";
        URI uri = URI.create(BASE_URL + POSTS_PATH + "?" + query);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("ScrapeCreators request failed for @{}: {}", normalizedHandle, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ScrapeCreators request interrupted for @{}", normalizedHandle);
            return List.of();
        }

        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            log.warn("ScrapeCreators rejected @{} ({}): {}",
                    normalizedHandle,
                    response.statusCode(),
                    body.length() > 300 ? body.substring(0, 300) : body);
            return List.of();
        }

        return parsePosts(response.body(), normalizedHandle);
    }

    static List<InstagramPost> parsePosts(String body, String handle) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("Could not parse ScrapeCreators response for @{}: {}", handle, e.getMessage());
            return List.of();
        }

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            log.info("No posts returned for @{}", handle);
            return List.of();
        }

        String fetchedAt = Instant.now().toString();
        List<InstagramPost> posts = new ArrayList<>();
        for (JsonNode item : items) {
            InstagramPost post = toPost(item, handle, fetchedAt);
            if (post != null) {
                posts.add(post);
            }
        }
        return posts;
    }

    private static InstagramPost toPost(JsonNode item, String handle, String fetchedAt) {
        String id = textOrNull(item.path("pk"));
        if (id == null) {
            id = textOrNull(item.path("id"));
        }
        String code = textOrNull(item.path("code"));
        if (id == null || code == null) {
            return null;
        }

        String caption = textOrNull(item.path("caption").path("text"));
        long takenAt = item.path("taken_at").asLong(0);
        String postedAt = takenAt > 0 ? Instant.ofEpochSecond(takenAt).toString() : null;

        return new InstagramPost(
                id,
                handle,
                code,
                caption,
                "https://www.instagram.com/p/" + code + "/",
                postedAt,
                mediaTypeLabel(item.path("media_type").asInt(0)),
                fetchedAt
        );
    }

    private static String mediaTypeLabel(int mediaType) {
        return switch (mediaType) {
            case 1 -> "photo";
            case 2 -> "video";
            case 8 -> "carousel";
            default -> "unknown";
        };
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asString(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    static String normalizeHandle(String handle) {
        String trimmed = handle.strip();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
