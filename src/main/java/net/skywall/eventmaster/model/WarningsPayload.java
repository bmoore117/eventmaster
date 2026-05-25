package net.skywall.eventmaster.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Outgoing Hermes webhook body for warning notifications. Sent as a separate
 * call from {@link WebhookPayload} so the agent receives "what's new" and
 * "what's degraded" as distinct messages (an events delivery never carries
 * warning material, and vice versa).
 *
 * <p>{@code resolved} carries warning dedup keys ({@code source|code}) that
 * were present in the previous run but are no longer firing — the
 * "recovery" signal so the agent can announce that a previously broken
 * source is healthy again. {@code current} is the full set of warnings still
 * firing this run.
 */
public record WarningsPayload(
        @JsonProperty("instructions") String instructions,
        @JsonProperty("triggered_at") String triggeredAt,
        @JsonProperty("current") List<RunWarning> current,
        @JsonProperty("resolved") List<String> resolved
) {}
