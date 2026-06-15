package net.skywall.eventmaster.utils;

import net.skywall.eventmaster.model.Event;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Date-aware classification helpers shared by the event store and Luma scraper.
 *
 * <p>Ports {@code _parse_event_date}, {@code is_past}, {@code is_in_next_n_days},
 * and {@code filter_prospective} from {@code parse_events.py}.
 */
public final class DateFilters {

    public static final int DEFAULT_WINDOW_DAYS = 21;

    private static final List<DateTimeFormatter> WITH_YEAR = List.of(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
                    .withResolverStyle(ResolverStyle.LENIENT)
    );

    private static final DateTimeFormatter NO_YEAR_WITH_DOW =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
                    .withResolverStyle(ResolverStyle.LENIENT);

    private DateFilters() {}

    /** Parse an event date string into a {@code LocalDate}, or empty if unparseable. */
    public static Optional<LocalDate> parseEventDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(LocalDate.parse(dateStr));
        } catch (DateTimeParseException _) {
            // Fall through to the natural-language formats below.
        }

        for (DateTimeFormatter f : WITH_YEAR) {
            try {
                return Optional.of(LocalDate.parse(dateStr, f));
            } catch (DateTimeParseException _) {
                // Try the next format.
            }
        }

        // "EEEE, MMMM d" — no year present. Assume the next occurrence: parse as
        // the current year first, bump to next year if the result is already past.
        int year = LocalDate.now().getYear();
        try {
            LocalDate parsed = LocalDate.parse(dateStr + ", " + year, NO_YEAR_WITH_DOW);
            if (parsed.isBefore(LocalDate.now())) {
                parsed = LocalDate.parse(dateStr + ", " + (year + 1), NO_YEAR_WITH_DOW);
            }
            return Optional.of(parsed);
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    /** True if the event's date is strictly before today. Unknown dates are kept (returns false). */
    public static boolean isPast(Event event) {
        return parseEventDate(event.date())
                .map(d -> d.isBefore(LocalDate.now()))
                .orElse(false);
    }

    /** True if the event is today or within the next N days. Unknown dates are kept. */
    public static boolean isInNextNDays(Event event, int days) {
        return parseEventDate(event.date())
                .map(d -> {
                    LocalDate today = LocalDate.now();
                    return !d.isBefore(today) && !d.isAfter(today.plusDays(days));
                })
                .orElse(true);
    }

    public static boolean isInNextNDays(Event event) {
        return isInNextNDays(event, DEFAULT_WINDOW_DAYS);
    }

    public static List<Event> filterProspective(List<Event> events) {
        return filterProspective(events, DEFAULT_WINDOW_DAYS);
    }

    public static List<Event> filterProspective(List<Event> events, int days) {
        return events.stream().filter(e -> isInNextNDays(e, days)).toList();
    }
}
