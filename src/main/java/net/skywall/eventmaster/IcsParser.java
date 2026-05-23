package net.skywall.eventmaster;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.skywall.eventmaster.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code .ics} attachments into {@link Event}s. Mirrors
 * {@code _parse_ics} from {@code parse_events.py}.
 *
 * <p>Reads each {@code VEVENT}'s DTSTART/DTEND as raw ICS-format strings
 * (e.g. {@code 20260412T190000Z}) and converts them to ISO date and
 * {@code HH:mm} time strings. This stays version-independent of ical4j's
 * {@code Temporal} type ladder.
 */
public final class IcsParser {

    private static final Logger log = LoggerFactory.getLogger(IcsParser.class);

    private static final DateTimeFormatter ICS_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter HH_MM =
            DateTimeFormatter.ofPattern("HH:mm");

    private IcsParser() {}

    public static List<Event> parse(byte[] payload) {
        List<Event> events = new ArrayList<>();
        try {
            Calendar cal = new CalendarBuilder().build(new ByteArrayInputStream(payload));
            for (CalendarComponent component : cal.getComponents()) {
                if (!(component instanceof VEvent vevent)) {
                    continue;
                }
                String summary = propValue(vevent, Property.SUMMARY, "");
                String location = emptyToNull(propValue(vevent, Property.LOCATION, ""));
                String description = emptyToNull(propValue(vevent, Property.DESCRIPTION, ""));
                String dtstartRaw = propValue(vevent, Property.DTSTART, null);
                String dtendRaw = propValue(vevent, Property.DTEND, null);

                events.add(new Event(
                        summary,
                        icsDate(dtstartRaw),
                        icsTime(dtstartRaw),
                        icsDate(dtendRaw),
                        icsTime(dtendRaw),
                        location,
                        description,
                        null,
                        "ics",
                        null, null, null, null
                ));
            }
        } catch (Exception e) {
            log.debug("ICS parse failed: {}", e.getMessage());
        }
        return events;
    }

    private static String propValue(VEvent vevent, String name, String fallback) {
        return vevent.<Property>getProperty(name)
                .map(Property::getValue)
                .orElse(fallback);
    }

    private static String icsDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = stripTrailingZ(raw);
        try {
            if (cleaned.contains("T")) {
                return LocalDateTime.parse(cleaned, ICS_DATE_TIME).toLocalDate().toString();
            }
            return LocalDate.parse(cleaned, DateTimeFormatter.BASIC_ISO_DATE).toString();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static String icsTime(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("T")) return null;
        try {
            return LocalDateTime.parse(stripTrailingZ(raw), ICS_DATE_TIME).format(HH_MM);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static String stripTrailingZ(String s) {
        return s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
