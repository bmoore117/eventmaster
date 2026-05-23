package net.skywall.eventmaster.model;

/** Per-run health snapshot delivered alongside the {@code newEvents} payload. */
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
