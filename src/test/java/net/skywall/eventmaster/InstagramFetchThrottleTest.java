package net.skywall.eventmaster;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramFetchThrottleTest {

    @Test
    void shouldFetch_whenNeverFetched() {
        assertTrue(InstagramFetchThrottle.shouldFetch(null, Instant.now(), 6));
    }

    @Test
    void shouldFetch_whenIntervalDisabled() {
        String lastFetched = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        assertTrue(InstagramFetchThrottle.shouldFetch(lastFetched, Instant.now(), 0));
    }

    @Test
    void shouldFetch_whenIntervalElapsed() {
        Instant now = Instant.parse("2026-05-25T12:00:00Z");
        String lastFetched = now.minus(7, ChronoUnit.HOURS).toString();
        assertTrue(InstagramFetchThrottle.shouldFetch(lastFetched, now, 6));
    }

    @Test
    void shouldNotFetch_whenWithinInterval() {
        Instant now = Instant.parse("2026-05-25T12:00:00Z");
        String lastFetched = now.minus(2, ChronoUnit.HOURS).toString();
        assertFalse(InstagramFetchThrottle.shouldFetch(lastFetched, now, 6));
    }

    @Test
    void shouldFetch_whenExactlyAtIntervalBoundary() {
        Instant now = Instant.parse("2026-05-25T12:00:00Z");
        String lastFetched = now.minus(6, ChronoUnit.HOURS).toString();
        assertTrue(InstagramFetchThrottle.shouldFetch(lastFetched, now, 6));
    }
}
