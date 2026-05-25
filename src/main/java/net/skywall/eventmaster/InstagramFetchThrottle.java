package net.skywall.eventmaster;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Gates ScrapeCreators calls behind a single global fetch window. The API has
 * no "since_id" parameter — every call returns the latest page — so the only
 * credit-saving lever is fetching less often.
 */
final class InstagramFetchThrottle {

    private InstagramFetchThrottle() {}

    /**
     * @param intervalHours {@code 0} disables throttling (fetch every run).
     */
    static boolean shouldFetch(String lastFetchedAtIso, Instant now, int intervalHours) {
        if (intervalHours <= 0) {
            return true;
        }
        if (lastFetchedAtIso == null || lastFetchedAtIso.isBlank()) {
            return true;
        }
        Instant lastFetched = Instant.parse(lastFetchedAtIso);
        Instant nextAllowed = lastFetched.plus(intervalHours, ChronoUnit.HOURS);
        return !now.isBefore(nextAllowed);
    }

    static String skipMessage(String lastFetchedAtIso, Instant now, int intervalHours) {
        Instant lastFetched = Instant.parse(lastFetchedAtIso);
        Duration elapsed = Duration.between(lastFetched, now);
        long hours = elapsed.toHours();
        long minutes = elapsed.toMinutesPart();
        String ago = hours > 0
                ? hours + "h " + minutes + "m"
                : minutes + "m";
        return "Skipping Instagram fetch — last run " + ago + " ago, interval " + intervalHours + "h";
    }
}
