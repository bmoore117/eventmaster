package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.utils.DateFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads and writes {@code upcoming_events.json} / {@code past_events.json}, and
 * handles the dedup + rotation logic. Mirrors {@code _load_json_list},
 * {@code _write_json_list}, {@code _event_key}, and {@code _rotate_and_classify}
 * from {@code fetch_events.py}.
 */
public final class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);
    private static final TypeReference<List<Event>> EVENT_LIST = new TypeReference<>() {};

    private final Path upcomingPath;
    private final Path pastPath;

    public EventStore(Path upcomingPath, Path pastPath) {
        this.upcomingPath = upcomingPath;
        this.pastPath = pastPath;
    }

    public List<Event> loadUpcoming() {
        return loadList(upcomingPath);
    }

    public List<Event> loadPast() {
        return loadList(pastPath);
    }

    public void writeUpcoming(List<Event> events) throws IOException {
        writeList(upcomingPath, events);
    }

    public void writePast(List<Event> events) throws IOException {
        writeList(pastPath, events);
    }

    /**
     * Compute the set of identity hints for an event. Two events are duplicates
     * if any of their hints overlap. Tiers, strongest to weakest:
     * <ul>
     *   <li>{@code luma:<normalised-luma-url>} — only emitted for real Luma URLs
     *       (host {@code lu.ma} or {@code luma.com}); Instagram permalinks are
     *       deliberately not used as an identity hint.
     *   <li>{@code td:<normalised-title>|<date>} — title (lowercased,
     *       punctuation-stripped, whitespace-collapsed) and date both present.
     *   <li>{@code dl:<date>|<normalised-location>} — date and location both
     *       present. Catches "same venue, same day, different title" cases.
     * </ul>
     * Events with no identifying information return an empty set and are not
     * dedup-mergeable with anything else.
     */
    public static Set<String> eventKeys(Event event) {
        Set<String> hints = new LinkedHashSet<>();

        String url = event.lumaUrl();
        if (isLumaUrl(url)) {
            hints.add("luma:" + normaliseLumaUrl(url));
        }

        String title = normaliseText(event.title());
        String date = event.date() == null ? "" : event.date().strip();
        String location = normaliseText(event.location());

        if (!title.isEmpty() && !date.isEmpty()) {
            hints.add("td:" + title + "|" + date);
        }
        if (!date.isEmpty() && !location.isEmpty()) {
            hints.add("dl:" + date + "|" + location);
        }

        return hints;
    }

    private static boolean isLumaUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("lu.ma") || host.equals("www.lu.ma")
                    || host.equals("luma.com") || host.equals("www.luma.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normaliseLumaUrl(String url) {
        String trimmed = url.split("\\?", 2)[0];
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normaliseText(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Order-preserving dedup using the same hint-set intersection that
     * {@link #rotateAndClassify} applies downstream. The first occurrence of
     * any hint cluster wins; subsequent events whose hints overlap are dropped.
     *
     * <p>Events with no hints (no Luma URL and no usable title|date or
     * date|location) are always kept — they cannot prove duplication against
     * anything else, so dropping them would be lossy.
     *
     * <p>Intended for cleaning up the comparison context handed to the
     * Instagram classifier: the on-disk upcoming list and this run's freshly
     * scraped Luma calendar almost always overlap, and we'd rather not pay
     * for those duplicate entries in the LLM prompt.
     */
    public static List<Event> dedupByHints(List<Event> events) {
        Set<String> seen = new HashSet<>();
        List<Event> result = new ArrayList<>(events.size());
        for (Event e : events) {
            Set<String> keys = eventKeys(e);
            if (keys.isEmpty() || Collections.disjoint(seen, keys)) {
                seen.addAll(keys);
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 1) Move any existing upcoming events whose date has now passed into past.
     * 2) Classify each new event into upcoming or past, skipping duplicates by
     *    intersecting hint sets ({@link #eventKeys}).
     */
    public RotateResult rotateAndClassify(
            List<Event> upcoming,
            List<Event> past,
            List<Event> newEvents
    ) {
        List<Event> rotatedToPast = upcoming.stream().filter(DateFilters::isPast).toList();
        List<Event> stillUpcoming = new ArrayList<>(
                DateFilters.filterProspective(
                        upcoming.stream().filter(e -> !DateFilters.isPast(e)).toList()
                )
        );

        Set<String> seenUpcoming = new HashSet<>();
        for (Event e : stillUpcoming) seenUpcoming.addAll(eventKeys(e));

        Set<String> seenPast = new HashSet<>();
        for (Event e : past) seenPast.addAll(eventKeys(e));
        for (Event e : rotatedToPast) seenPast.addAll(eventKeys(e));

        List<Event> newlyAddedUpcoming = new ArrayList<>();
        List<Event> newlyAddedPast = new ArrayList<>();

        for (Event e : newEvents) {
            Set<String> keys = eventKeys(e);
            if (keys.isEmpty()) {
                // No hints means we can neither prove duplication nor
                // distinguish it from junk; skip rather than collapse all such
                // events to one synthetic bucket.
                continue;
            }
            if (DateFilters.isPast(e)) {
                if (Collections.disjoint(seenPast, keys)) {
                    seenPast.addAll(keys);
                    newlyAddedPast.add(e);
                }
            } else if (DateFilters.isInNextNDays(e) && Collections.disjoint(seenUpcoming, keys)) {
                seenUpcoming.addAll(keys);
                newlyAddedUpcoming.add(e);
            }
        }

        List<Event> finalUpcoming = new ArrayList<>(stillUpcoming);
        finalUpcoming.addAll(newlyAddedUpcoming);

        List<Event> finalPast = new ArrayList<>(past);
        finalPast.addAll(rotatedToPast);
        finalPast.addAll(newlyAddedPast);

        return new RotateResult(finalUpcoming, finalPast, newlyAddedUpcoming);
    }

    private static List<Event> loadList(Path path) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Json.MAPPER.readValue(Files.readAllBytes(path), EVENT_LIST));
        } catch (IOException | JacksonException e) {
            // JacksonException is unchecked in Jackson 3.x — must be caught
            // explicitly to honour the "start fresh on malformed JSON" intent.
            log.warn("Could not parse {} — starting fresh", path.getFileName());
            return new ArrayList<>();
        }
    }

    private static void writeList(Path path, List<Event> events) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, Json.PRETTY.writeValueAsBytes(events));
    }

    public record RotateResult(List<Event> upcoming, List<Event> past, List<Event> newlyAdded) {}
}
