package net.skywall.eventmaster.utils;

import net.skywall.eventmaster.model.EmailMessage;
import net.skywall.eventmaster.LumaScraper;
import net.skywall.eventmaster.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides how to extract events from a single {@link EmailMessage}. Order
 * mirrors {@code parse_email} in {@code parse_events.py}:
 * <ol>
 *   <li>Any {@code .ics} attachment ({@link IcsParser}).</li>
 *   <li>The first Luma URL in the plain-text body ({@link LumaScraper}).</li>
 *   <li>Heuristic date/time scraping of the body text ({@link BodyTextParser}).</li>
 * </ol>
 * Returned events are already stamped with provenance (source email/message id,
 * fallback title from subject, {@code fetched_at}).
 */
public final class EmailParser {

    private static final Logger log = LoggerFactory.getLogger(EmailParser.class);

    // Matches luma.com/<slug> or lu.ma/<slug>, capturing slug and optional query string.
    private static final Pattern LUMA_URL_RE = Pattern.compile(
            "https?://(?:www\\.)?(?:luma\\.com|lu\\.ma)/([a-zA-Z0-9]+)(\\?[^\\s<>\"']*)?");

    private EmailParser() {}

    public static List<Event> parse(EmailMessage msg) {
        List<Event> events = tryAttachments(msg);
        if (!events.isEmpty()) {
            return enrich(events, msg);
        }

        String lumaUrl = extractLumaUrl(msg.text());
        if (lumaUrl != null) {
            log.info("  -> found Luma URL: {}", lumaUrl);
            List<Event> fromLuma = LumaScraper.fetchEvent(lumaUrl);
            if (!fromLuma.isEmpty()) {
                return enrich(fromLuma, msg);
            }
        }

        return enrich(BodyTextParser.parse(msg.text()), msg);
    }

    private static List<Event> tryAttachments(EmailMessage msg) {
        for (EmailMessage.Attachment att : msg.attachments()) {
            String filename = att.filename();
            if (filename != null && filename.toLowerCase().endsWith(".ics")) {
                List<Event> events = IcsParser.parse(att.payload());
                if (!events.isEmpty()) {
                    return events;
                }
            }
        }
        return List.of();
    }

    static String extractLumaUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = LUMA_URL_RE.matcher(text);
        if (!m.find()) {
            return null;
        }
        String slug = m.group(1);
        String query = m.group(2);
        return "https://lu.ma/" + slug + (query == null ? "" : query);
    }

    private static List<Event> enrich(List<Event> events, EmailMessage msg) {
        if (events.isEmpty()) {
            return events;
        }
        String fetchedAt = Instant.now().toString();
        return events.stream()
                .map(e -> e.enriched(msg.subject(), msg.from(), msg.messageId(), null, fetchedAt))
                .toList();
    }
}
