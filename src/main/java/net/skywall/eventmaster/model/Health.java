package net.skywall.eventmaster.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Per-run health snapshot delivered alongside the {@code newEvents} payload. */
@JsonNaming(SnakeCaseStrategy.class)
public record Health(
        String lastRun,
        int emailsProcessedLastRun,
        int upcomingEvents,
        int pastEvents,
        int consecutiveFailures,
        String lastError,
        String lastErrorTime
) {
    public boolean hasErrors() {
        return consecutiveFailures > 0 || (lastError != null && !lastError.isBlank());
    }
}
