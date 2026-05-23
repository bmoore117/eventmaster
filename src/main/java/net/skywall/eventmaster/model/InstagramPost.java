package net.skywall.eventmaster.model;

/**
 * A normalised Instagram post, ready for the Hermes webhook payload.
 */
public record InstagramPost(
        String id,
        String handle,
        String code,
        String caption,
        String permalink,
        String postedAt,
        String mediaType,
        String fetchedAt
) {}
