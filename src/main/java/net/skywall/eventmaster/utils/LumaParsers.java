package net.skywall.eventmaster.utils;


import net.skywall.eventmaster.Json;
import net.skywall.eventmaster.LumaScraper;
import net.skywall.eventmaster.model.Event;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three-tier Luma page parser. Mirrors {@code _from_jsonld},
 * {@code _from_next_data}, {@code _from_luma_calendar_data}, and
 * {@code _scrape_luma_html} from {@code parse_events.py}.
 *
 * <p>Callers (typically {@link LumaScraper}) should run {@link #parsePage} which
 * walks all three tiers in order and returns the first non-empty result.
 */
public final class LumaParsers {

    private static final Logger log = LoggerFactory.getLogger(LumaParsers.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> EVENT_TYPES =
            Set.of("Event", "SocialEvent", "BusinessEvent", "MusicEvent");

    private static final Pattern DATE_RE = Pattern.compile(
            "((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\w*,\\s+\\w+ \\d{1,2})");
    private static final Pattern TIME_RE = Pattern.compile(
            "(\\d{1,2}:\\d{2}(?:\\s*[\u2013\\-]\\s*\\d{1,2}:\\d{2})?\\s*(?:AM|PM))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS_RE = Pattern.compile(
            "\\d+\\s+[A-Z][^\\n]+(?:St|Ave|Blvd|Rd|Dr|Ln|Way|Pl|Place)[^\\n]*");

    private LumaParsers() {}

    /** Walk JSON-LD, then {@code __NEXT_DATA__}, then last-resort HTML scrape. */
    public static List<Event> parsePage(Document doc, String sourceUrl) {
        Elements ldScripts = doc.select("script[type=application/ld+json]");
        for (Element script : ldScripts) {
            try {
                JsonNode root = Json.MAPPER.readTree(script.data());
                List<Event> events = fromJsonLd(root, sourceUrl);
                if (!events.isEmpty()) {
                    return events;
                }
            } catch (JacksonException e) {
                log.debug("Skipping malformed ld+json on {}: {}", sourceUrl, e.getOriginalMessage());
            }
        }

        Element nextData = doc.getElementById("__NEXT_DATA__");
        if (nextData != null) {
            try {
                JsonNode root = Json.MAPPER.readTree(nextData.data());
                List<Event> events = fromNextData(root, sourceUrl);
                if (!events.isEmpty()) {
                    return events;
                }
            } catch (JacksonException e) {
                log.debug("Skipping malformed __NEXT_DATA__ on {}: {}", sourceUrl, e.getOriginalMessage());
            }
        }

        return scrapeHtml(doc, sourceUrl);
    }

    // --- Tier 1: JSON-LD ---

    static List<Event> fromJsonLd(JsonNode data, String sourceUrl) {
        if (data.isArray()) {
            for (JsonNode item : data) {
                List<Event> result = fromJsonLd(item, sourceUrl);
                if (!result.isEmpty()) {
                    return result;
                }
            }
            return List.of();
        }
        if (!data.isObject() || !EVENT_TYPES.contains(data.path("@type").asString())) {
            return List.of();
        }

        String start = data.path("startDate").asString(null);
        String end = data.path("endDate").asString(null);
        JsonNode loc = data.path("location");

        String locName = loc.isObject() ? loc.path("name").asString("") : loc.asString("");
        String locAddress = "";
        JsonNode addressNode = loc.path("address");
        if (addressNode.isObject()) {
            locAddress = addressNode.path("streetAddress").asString("");
        }
        String location = joinNonBlank(", ", locName, locAddress);

        return List.of(new Event(
                data.path("name").asString(""),
                isoDate(start),
                isoTime(start),
                isoDate(end),
                isoTime(end),
                location.isEmpty() ? null : location,
                emptyToNull(data.path("description").asString(null)),
                sourceUrl,
                "luma_jsonld",
                null, null, null, null, null
        ));
    }

    // --- Tier 2: __NEXT_DATA__ ---

    static List<Event> fromNextData(JsonNode root, String sourceUrl) {
        JsonNode props = root.path("props").path("pageProps");
        JsonNode initialData = props.path("initialData");

        if ("calendar".equals(initialData.path("kind").asString())) {
            return fromLumaCalendarData(initialData.path("data"), sourceUrl);
        }

        JsonNode event = firstObject(
                props.path("event"),
                props.path("initialEvent"),
                props.path("eventData"),
                initialData.path("data").path("event"),
                initialData.path("event")
        );
        if (event == null) {
            return List.of();
        }

        String startAt = event.path("start_at").asString(null);
        String endAt = event.path("end_at").asString(null);

        JsonNode geo = firstObject(
                event.path("geo_address_json"),
                event.path("geo_address_info")
        );
        String location = null;
        if (geo != null) {
            location = firstNonBlank(
                    geo.path("full_address").asString(null),
                    geo.path("short_address").asString(null)
            );
        }
        if (location == null) {
            location = event.path("location").asString(null);
        }

        return List.of(new Event(
                event.path("name").asString(""),
                isoDate(startAt),
                isoTime(startAt),
                isoDate(endAt),
                isoTime(endAt),
                emptyToNull(location),
                emptyToNull(event.path("description").asString(null)),
                sourceUrl,
                "luma_nextdata",
                null, null, null, null, null
        ));
    }

    static List<Event> fromLumaCalendarData(JsonNode data, String sourceUrl) {
        JsonNode featured = data.path("featured_items");
        if (!featured.isArray()) {
            return List.of();
        }

        List<Event> events = new ArrayList<>();
        for (JsonNode item : featured) {
            JsonNode ev = item.path("event");
            String name = ev.path("name").asString("");
            if (!ev.isObject() || name.isEmpty()) {
                continue;
            }

            String startAt = firstNonBlank(
                    ev.path("start_at").asString(null),
                    item.path("start_at").asString(null)
            );
            String endAt = ev.path("end_at").asString(null);

            JsonNode geo = firstObject(
                    ev.path("geo_address_info"),
                    ev.path("geo_address_json")
            );
            String location = null;
            if (geo != null) {
                location = firstNonBlank(
                        geo.path("full_address").asString(null),
                        geo.path("short_address").asString(null)
                );
            }

            String slug = ev.path("url").asString("");
            String eventUrl = slug.isEmpty() ? sourceUrl : "https://lu.ma/" + slug;

            events.add(new Event(
                    name,
                    isoDate(startAt),
                    isoTime(startAt),
                    isoDate(endAt),
                    isoTime(endAt),
                    emptyToNull(location),
                    emptyToNull(ev.path("description").asString(null)),
                    eventUrl,
                    "luma_calendar_nextdata",
                    null, null, null, null, null
            ));
        }
        return events;
    }

    // --- Tier 3: HTML scrape (best-effort fallback) ---

    static List<Event> scrapeHtml(Document doc, String sourceUrl) {
        Element h1 = doc.selectFirst("h1");
        if (h1 == null) {
            return List.of();
        }
        String title = h1.text().strip();
        if (title.isEmpty()) {
            return List.of();
        }

        String text = doc.wholeText();
        String date = matchOrNull(DATE_RE, text);
        String time = matchOrNull(TIME_RE, text);
        Matcher addrMatcher = ADDRESS_RE.matcher(text);
        String address = addrMatcher.find() ? addrMatcher.group(0).strip() : null;

        return List.of(new Event(
                title, date, time, null, null,
                address, null, sourceUrl, "luma_html_scrape",
                null, null, null, null, null
        ));
    }

    // --- ISO date/time helpers (port of _iso_date / _iso_time) ---

    /** Returns the date portion of an ISO datetime; on failure, returns the input unchanged (matches Python). */
    public static String isoDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseIso(value)
                .map(dt -> dt.toLocalDate().toString())
                .orElse(value);
    }

    /** Returns the {@code HH:mm} time portion of an ISO datetime, or null if absent/unparseable. */
    public static String isoTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseIso(value).map(dt -> dt.format(HH_MM)).orElse(null);
    }

    private static Optional<LocalDateTime> parseIso(String s) {
        Instant now = Instant.now();
        try { return Optional.of(OffsetDateTime.parse(s).toLocalDateTime()); } catch (DateTimeParseException _) {}
        try { return Optional.of(LocalDateTime.parse(s)); } catch (DateTimeParseException _) {}
        try { return Optional.of(Instant.parse(s).atOffset(ZoneOffset.systemDefault().getRules().getOffset(now)).toLocalDateTime()); } catch (DateTimeParseException _) {}
        try { return Optional.of(LocalDate.parse(s).atStartOfDay()); } catch (DateTimeParseException _) {}
        return Optional.empty();
    }

    // --- Small helpers ---

    private static JsonNode firstObject(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isObject()) {
                return n;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String matchOrNull(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1) : null;
    }
}
