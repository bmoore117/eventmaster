package net.skywall.eventmaster.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Outgoing Hermes webhook body. Field naming mixes snake_case and camelCase
 * because the Hermes route template ({@code hermes/config-route.example.yaml})
 * substitutes those exact names — we honour them verbatim.
 */
public record WebhookPayload(
        @JsonProperty("instructions") String instructions,
        @JsonProperty("triggered_at") String triggeredAt,
        @JsonProperty("hasErrors") boolean hasErrors,
        @JsonProperty("newEvents") List<Event> newEvents,
        @JsonProperty("newInstagramPosts") List<InstagramPost> newInstagramPosts,
        @JsonProperty("health") Health health
) {}
