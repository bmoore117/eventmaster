package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.InstagramPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

        // Assign a stable per-call id to each upcoming event so the LLM can
        // point at any of them (including ICS- or body-text-sourced events
        // that have no lumaUrl) when reporting a match.
        Map<String, Event> upcomingById = indexUpcoming(upcomingEvents);

        String systemPrompt = loadPrompt();
        String userPrompt = buildUserPrompt(posts, upcomingById, LocalDate.now());
        String response = completions.complete(systemPrompt, userPrompt);
        return parseEvents(response, posts, upcomingById);
    }

    static Map<String, Event> indexUpcoming(List<Event> upcomingEvents) {
        Map<String, Event> indexed = new LinkedHashMap<>();
        for (int i = 0; i < upcomingEvents.size(); i++) {
            indexed.put("u" + i, upcomingEvents.get(i));
        }
        return indexed;
    }

    private String loadPrompt() throws IOException {
        if (!Files.isRegularFile(config.instagramClassifierPromptPath)) {
            throw new IOException("Instagram classifier prompt not found: "
                    + config.instagramClassifierPromptPath);
        }
        return Files.readString(config.instagramClassifierPromptPath, StandardCharsets.UTF_8).strip();
    }

    static String buildUserPrompt(List<InstagramPost> posts, Map<String, Event> upcomingById, LocalDate currentDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentDate", currentDate.toString());
        payload.put("posts", posts.stream().map(InstagramPostClassifier::postInput).toList());
        payload.put("upcomingEvents", upcomingById.entrySet().stream()
                .map(e -> eventInput(e.getKey(), e.getValue()))
                .toList());
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
        item.put("postedDate", postedDate(post.postedAt()));
        item.put("mediaType", post.mediaType());
        return item;
    }

    private static String postedDate(String postedAt) {
        if (postedAt == null || postedAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(postedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> eventInput(String id, Event event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", event.title());
        item.put("date", event.date());
        item.put("time", event.time());
        item.put("location", event.location());
        item.put("description", event.description());
        item.put("lumaUrl", event.lumaUrl());
        return item;
    }

    static List<Event> parseEvents(String response, List<InstagramPost> posts, Map<String, Event> upcomingById)
            throws IOException {
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
        int matchedCount = 0;

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

            String matchedId = textOrNull(item.path("matchedUpcomingEventId"));
            if (matchedId != null) {
                if (upcomingById.containsKey(matchedId)) {
                    // Post is announcing an event we already track — don't
                    // emit a separate Event; the post id will still be marked
                    // seen by the caller so we won't re-classify it.
                    matchedCount++;
                    continue;
                }
                log.warn("Classifier returned unknown upcoming event id {} — treating post {} as unmatched",
                        matchedId, id);
            }

            eventCount++;
            events.add(toEvent(item, post));
        }

        log.info("Instagram classification: {} event post(s), {} matched to existing, {} non-event post(s)",
                eventCount, matchedCount, nonEventCount);
        return events;
    }

    private static Event toEvent(JsonNode item, InstagramPost post) {
        String title = textOrNull(item.path("title"));
        if (title == null || title.isBlank()) {
            title = "Instagram post from @" + post.handle();
        }

        String description = textOrNull(item.path("description"));
        if (description == null || description.isBlank()) {
            description = post.caption();
        }

        // Unmatched posts get their permalink in the lumaUrl slot so the
        // agent has a clickable link. EventStore.eventKeys treats it as a
        // non-Luma URL and falls back to title/date/location hints for dedup.
        return new Event(
                title,
                textOrNull(item.path("date")),
                textOrNull(item.path("time")),
                textOrNull(item.path("endDate")),
                textOrNull(item.path("endTime")),
                textOrNull(item.path("location")),
                description,
                post.permalink(),
                "instagram_agent",
                "instagram:" + post.handle(),
                null,
                post.id(),
                post.fetchedAt(),
                null
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
