package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.InstagramPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends new Instagram posts to the Hermes completions API and maps event posts
 * into {@link Event} records for the main pipeline.
 */
public final class InstagramPostClassifier {

    private static final Logger log = LoggerFactory.getLogger(InstagramPostClassifier.class);

    private final Config config;
    private final HermesCompletionsClient completions;

    public InstagramPostClassifier(Config config) {
        this.config = config;
        this.completions = new HermesCompletionsClient(config);
    }

    public List<Event> classifyPosts(List<InstagramPost> posts, List<Event> upcomingEvents) throws IOException {
        if (posts.isEmpty()) {
            return List.of();
        }
        if (!completions.api().enabled()) {
            log.warn("Hermes API disabled — skipping Instagram classification for {} post(s)", posts.size());
            return List.of();
        }

        String systemPrompt = loadPrompt();
        String userPrompt = buildUserPrompt(posts, upcomingEvents);
        log.info("System prompt: {}", systemPrompt);
        log.info("User prompt: {}", userPrompt);
        String response = completions.complete(systemPrompt, userPrompt);
        return parseEvents(response, posts);
    }

    private String loadPrompt() throws IOException {
        if (!Files.isRegularFile(config.instagramClassifierPromptPath)) {
            throw new IOException("Instagram classifier prompt not found: "
                    + config.instagramClassifierPromptPath);
        }
        return Files.readString(config.instagramClassifierPromptPath, StandardCharsets.UTF_8).strip();
    }

    static String buildUserPrompt(List<InstagramPost> posts, List<Event> upcomingEvents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("posts", posts.stream().map(InstagramPostClassifier::postInput).toList());
        payload.put("upcomingEvents", upcomingEvents.stream().map(InstagramPostClassifier::eventInput).toList());
        try {
            return Json.PRETTY.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise classifier input", e);
        }
    }

    private static Map<String, Object> postInput(InstagramPost post) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", post.id());
        item.put("handle", post.handle());
        item.put("caption", post.caption());
        item.put("permalink", post.permalink());
        item.put("postedAt", post.postedAt());
        item.put("mediaType", post.mediaType());
        return item;
    }

    private static Map<String, Object> eventInput(Event event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", event.title());
        item.put("date", event.date());
        item.put("time", event.time());
        item.put("location", event.location());
        item.put("description", event.description());
        item.put("lumaUrl", event.lumaUrl());
        return item;
    }

    static List<Event> parseEvents(String response, List<InstagramPost> posts) throws IOException {
        JsonNode root = Json.MAPPER.readTree(extractJson(response));
        JsonNode results = root.path("posts");
        if (!results.isArray()) {
            throw new IOException("Classifier response missing posts array");
        }

        Map<String, InstagramPost> postsById = new LinkedHashMap<>();
        for (InstagramPost post : posts) {
            postsById.put(post.id(), post);
        }

        List<Event> events = new ArrayList<>();
        int eventCount = 0;
        int nonEventCount = 0;

        for (JsonNode item : results) {
            String id = textOrNull(item.path("id"));
            if (id == null) {
                continue;
            }

            InstagramPost post = postsById.get(id);
            if (post == null) {
                log.warn("Classifier returned unknown post id {}", id);
                continue;
            }

            if (!item.path("isEvent").asBoolean(false)) {
                nonEventCount++;
                continue;
            }

            eventCount++;
            events.add(toEvent(item, post));
        }

        log.info("Instagram classification: {} event post(s), {} non-event post(s)",
                eventCount, nonEventCount);
        return events;
    }

    private static Event toEvent(JsonNode item, InstagramPost post) {
        String matchedUrl = textOrNull(item.path("matchedUpcomingEventUrl"));
        String url = (matchedUrl != null) ? matchedUrl : post.permalink();
        String title = textOrNull(item.path("title"));
        if (title == null || title.isBlank()) {
            title = "Instagram post from @" + post.handle();
        }

        String description = textOrNull(item.path("description"));
        if (description == null || description.isBlank()) {
            description = post.caption();
        }

        return new Event(
                title,
                textOrNull(item.path("date")),
                textOrNull(item.path("time")),
                textOrNull(item.path("endDate")),
                textOrNull(item.path("endTime")),
                textOrNull(item.path("location")),
                description,
                url,
                "instagram_agent",
                "instagram:" + post.handle(),
                null,
                post.id(),
                post.fetchedAt()
        );
    }

    static String extractJson(String content) {
        String trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int start = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        if (start >= 0 && end > start) {
            return trimmed.substring(start + 1, end).strip();
        }
        return trimmed;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asString(null);
        return (value == null || value.isBlank()) ? null : value;
    }
}
