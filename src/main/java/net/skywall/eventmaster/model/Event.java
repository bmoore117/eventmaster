package net.skywall.eventmaster.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A normalised event, ready for serialisation into {@code upcoming_events.json}
 * or the Hermes webhook payload.
 *
 * <p>Fields are kept as strings rather than {@code LocalDate}/{@code LocalTime}
 * so that JSON round-trips are byte-identical to the Python implementation and
 * so that partially-known events (e.g. body-text scrapes with no time) can be
 * represented without lossy conversions. Typed parsing happens in
 * {@link lumaevents.DateFilters}.
 */
@JsonNaming(SnakeCaseStrategy.class)
public record Event(
        String title,
        String date,
        String time,
        String endDate,
        String endTime,
        String location,
        String description,
        String lumaUrl,
        String parseMethod,
        String source,
        String sourceEmail,
        String sourceMessageId,
        String fetchedAt
) {
    /**
     * Stamp provenance onto a parser-emitted event. Empty/blank titles fall
     * back to {@code fallbackTitle} (typically the email subject). A non-null
     * {@code newSource} overrides any existing source; otherwise the existing
     * value is preserved.
     */
    public Event enriched(
            String fallbackTitle,
            String sourceEmail,
            String sourceMessageId,
            String newSource,
            String fetchedAt
    ) {
        return new Event(
                (title == null || title.isBlank()) ? fallbackTitle : title,
                date, time, endDate, endTime, location, description, lumaUrl, parseMethod,
                newSource != null ? newSource : source,
                sourceEmail,
                sourceMessageId,
                fetchedAt
        );
    }
}
