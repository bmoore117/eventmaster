package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.utils.DateFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * Canonical dedup key. Prefers the normalised Luma URL (lowercased, query
     * stripped, trailing slash removed); falls back to {@code "title|date"}.
     */
    public static String eventKey(Event event) {
        String url = event.lumaUrl();
        if (url != null && !url.isBlank()) {
            String trimmed = url.split("\\?", 2)[0];
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()) {
                return trimmed.toLowerCase();
            }
        }
        String title = event.title() == null ? "" : event.title().strip().toLowerCase();
        String date = event.date() == null ? "" : event.date().strip();
        return title + "|" + date;
    }

    /**
     * 1) Move any existing upcoming events whose date has now passed into past.
     * 2) Classify each new event into upcoming or past, skipping duplicates.
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
        for (Event e : stillUpcoming) seenUpcoming.add(eventKey(e));

        Set<String> seenPast = new HashSet<>();
        for (Event e : past) seenPast.add(eventKey(e));
        for (Event e : rotatedToPast) seenPast.add(eventKey(e));

        List<Event> newlyAddedUpcoming = new ArrayList<>();
        List<Event> newlyAddedPast = new ArrayList<>();

        for (Event e : newEvents) {
            String key = eventKey(e);
            if (DateFilters.isPast(e)) {
                if (seenPast.add(key)) {
                    newlyAddedPast.add(e);
                }
            } else if (DateFilters.isInNextNDays(e) && seenUpcoming.add(key)) {
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
