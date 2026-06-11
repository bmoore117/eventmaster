package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStoreTest {

    private static String futureDate(int daysAhead) {
        return LocalDate.now().plusDays(daysAhead).toString();
    }

    private static Event event(
            String title, String date, String location, String lumaUrl
    ) {
        return new Event(title, date, null, null, null, location, null,
                lumaUrl, "test", null, null, null, null, null);
    }

    private static Event eventNotified(String title, String date, String notifiedAt) {
        return eventNotified(title, date, null, notifiedAt);
    }

    private static Event eventNotified(String title, String date, String location, String notifiedAt) {
        return new Event(title, date, null, null, null, location, null,
                null, "test", null, null, null, null, notifiedAt);
    }

    @Test
    void eventKeys_emitsLumaHintOnlyForRealLumaHosts() {
        Set<String> luma = EventStore.eventKeys(event("X", "2026-05-30", null, "https://lu.ma/xyz"));
        assertTrue(luma.contains("luma:https://lu.ma/xyz"));

        Set<String> instagram = EventStore.eventKeys(event("X", "2026-05-30", null,
                "https://www.instagram.com/p/ABC/"));
        assertFalse(instagram.stream().anyMatch(s -> s.startsWith("luma:")),
                "Instagram permalinks must not produce a luma: hint");
        assertTrue(instagram.contains("td:x|2026-05-30"));
    }

    @Test
    void eventKeys_normalisesTitleAndLocation() {
        Set<String> keys = EventStore.eventKeys(event(
                "  Beach Bash!  ", "2026-05-30", "South Pointe Park, Miami Beach", null));
        assertTrue(keys.contains("td:beach bash|2026-05-30"));
        assertTrue(keys.contains("dl:2026-05-30|south pointe park miami beach"));
    }

    @Test
    void eventKeys_skipsHintsForMissingFields() {
        Set<String> noDate = EventStore.eventKeys(event("Title only", null, "Somewhere", null));
        assertTrue(noDate.isEmpty(), "Without a date there is no usable hint");

        Set<String> blank = EventStore.eventKeys(event(null, null, null, null));
        assertTrue(blank.isEmpty());
    }

    @Test
    void rotateAndClassify_dedupsCrossSourceByTitleAndDate() {
        Event ics = eventNotified("Open House", futureDate(3), "Adam's Studio", "2026-01-01T00:00:00Z");
        Event scrapedFromInstagram = event("open house!", futureDate(3), "Adam Studio", null);

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(
                List.of(ics), List.of(), List.of(scrapedFromInstagram));

        assertEquals(0, result.newlyStored().size(),
                "Instagram-derived event with matching title|date should dedup against the ICS event");
        assertEquals(0, result.toNotify().size());
        assertEquals(1, result.upcoming().size());
    }

    @Test
    void rotateAndClassify_dedupsByDateAndLocationWhenTitlesDiffer() {
        Event existing = eventNotified("Spring Open House", futureDate(3), "Adam's Studio", "2026-01-01T00:00:00Z");
        Event other = event("Studio Spring Showcase", futureDate(3), "Adam's Studio", null);

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(
                List.of(existing), List.of(), List.of(other));

        assertEquals(0, result.newlyStored().size(),
                "Same date + same venue should collapse to one event even with different titles");
        assertEquals(0, result.toNotify().size());
    }

    @Test
    void rotateAndClassify_keepsDistinctEventsWithoutOverlap() {
        Event a = eventNotified("Beach Bash", futureDate(3), "South Pointe", "2026-01-01T00:00:00Z");
        Event b = event("Backyard BBQ", futureDate(4), "Eli's House", null);

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(
                List.of(a), List.of(), List.of(b));

        assertEquals(1, result.newlyStored().size());
        assertEquals(1, result.toNotify().size());
        assertEquals(2, result.upcoming().size());
    }

    @Test
    void rotateAndClassify_storesFarFutureEventWithoutNotifying() {
        Event farFuture = event("Monthly Mixer", futureDate(20), "Downtown", null);

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(List.of(), List.of(), List.of(farFuture));

        assertEquals(1, result.newlyStored().size());
        assertEquals(0, result.toNotify().size(), "Outside the 7-day window — store only");
        assertEquals(1, result.upcoming().size());
        assertFalse(result.upcoming().getFirst().isNotified());
    }

    @Test
    void rotateAndClassify_notifiesStoredEventWhenItEntersWindow() {
        Event stored = event("Monthly Mixer", futureDate(3), "Downtown", null);

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(List.of(stored), List.of(), List.of());

        assertEquals(0, result.newlyStored().size());
        assertEquals(1, result.toNotify().size());
    }

    @Test
    void rotateAndClassify_skipsAlreadyNotifiedEventsInWindow() {
        Event notified = eventNotified("Soon", futureDate(2), "2026-01-01T00:00:00Z");

        EventStore store = new EventStore(null, null);
        EventStore.RotateResult result = store.rotateAndClassify(List.of(notified), List.of(), List.of());

        assertEquals(0, result.toNotify().size());
    }

    @Test
    void markNotified_stampsMatchingUpcomingEvents() {
        Event stored = event("Soon", futureDate(2), null, null);
        String stamp = "2026-06-10T12:00:00Z";

        List<Event> marked = EventStore.markNotified(List.of(stored), List.of(stored), stamp);

        assertEquals(stamp, marked.getFirst().notifiedAt());
    }

    @Test
    void dedupByHints_collapsesDuplicatesPreservingOrderAndFirstOccurrence() {
        Event onDisk = event("Beach Bash", "2026-05-30", "South Pointe", "https://lu.ma/beach");
        Event distinct = event("Backyard BBQ", "2026-06-01", "Eli's House", null);
        Event rescrape = event("Beach Bash", "2026-05-30", "South Pointe", "https://lu.ma/beach");
        Event titleVariant = event("beach bash!", "2026-05-30", null, null);

        List<Event> deduped = EventStore.dedupByHints(List.of(onDisk, distinct, rescrape, titleVariant));

        assertEquals(List.of(onDisk, distinct), deduped,
                "Re-scrapes and title variants should collapse to the first occurrence");
    }

    @Test
    void dedupByHints_keepsEventsWithNoUsableHints() {
        Event hintless1 = event(null, null, null, null);
        Event hintless2 = event("Title only", null, null, null);
        Event withHints = event("X", "2026-05-30", null, null);

        List<Event> deduped = EventStore.dedupByHints(List.of(hintless1, hintless2, withHints));

        assertEquals(3, deduped.size(),
                "Events with no hints can't prove duplication and must be kept");
    }

    @Test
    void dedupByHints_returnsEmptyListForEmptyInput() {
        assertTrue(EventStore.dedupByHints(List.of()).isEmpty());
    }
}
