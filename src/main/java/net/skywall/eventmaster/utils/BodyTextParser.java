package net.skywall.eventmaster.utils;

import net.skywall.eventmaster.model.Event;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last-resort regex-based date/time extraction from a plain-text email body.
 * Mirrors {@code _parse_body} from {@code parse_events.py} — produces a single
 * "best-guess" event when no Luma URL or {@code .ics} attachment is available.
 */
public final class BodyTextParser {

    private static final Pattern[] DATE_PATTERNS = {
            Pattern.compile("\\b(\\w+ \\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),
            Pattern.compile("\\b(\\d{1,2}/\\d{1,2}/\\d{4})\\b")
    };

    private static final Pattern[] TIME_PATTERNS = {
            Pattern.compile("\\b(\\d{1,2}:\\d{2}\\s*(?:am|pm))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(\\d{1,2}\\s*(?:am|pm))\\b", Pattern.CASE_INSENSITIVE)
    };

    private BodyTextParser() {}

    public static List<Event> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String date = firstMatch(DATE_PATTERNS, text);
        if (date == null) {
            return List.of();
        }
        String time = firstMatch(TIME_PATTERNS, text);

        String description = text.length() > 500 ? text.substring(0, 500).strip() : text.strip();

        return List.of(new Event(
                null, date, time, null, null,
                null, description, null, "body_text",
                null, null, null, null
        ));
    }

    private static String firstMatch(Pattern[] patterns, String input) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(input);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
