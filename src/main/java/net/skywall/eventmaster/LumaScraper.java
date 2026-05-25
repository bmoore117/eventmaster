package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.utils.LumaParsers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches Luma event pages and calendar/group pages, delegating parsing to
 * {@link LumaParsers}. Mirrors {@code _fetch_luma_event} and
 * {@code fetch_luma_calendar} from {@code parse_events.py}.
 */
public final class LumaScraper {

    private static final Logger log = LoggerFactory.getLogger(LumaScraper.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private LumaScraper() {}

    /** Fetch and parse a single Luma event page. Returns an empty list on any failure. */
    public static List<Event> fetchEvent(String url) {
        String html;
        try {
            html = fetchHtml(url);
        } catch (LumaFetchException e) {
            log.warn("Failed to fetch Luma event page {}: {}", url, e.getMessage());
            return List.of();
        }
        Document doc = Jsoup.parse(html, url);
        return LumaParsers.parsePage(doc, url);
    }

    /**
     * Fetch a Luma calendar/group page, extract its featured events, and follow
     * each event URL to get the full details (descriptions, etc.).
     *
     * <p>Throws {@link LumaFetchException} when the top-level calendar page
     * itself fails to load (non-2xx, network IO, interrupt). Per-event-page
     * failures inside the calendar fetch are still soft — the stub from the
     * calendar listing is kept.
     */
    public static List<Event> fetchCalendar(String slugOrUrl) {
        String url = slugOrUrl.startsWith("http://") || slugOrUrl.startsWith("https://")
                ? slugOrUrl
                : "https://lu.ma/" + slugOrUrl;

        String html = fetchHtml(url);

        Document doc = Jsoup.parse(html, url);
        List<Event> stubs = LumaParsers.parsePage(doc, url);
        if (stubs.isEmpty()) {
            return List.of();
        }

        String today = LocalDate.now().toString();
        List<Event> upcomingStubs = stubs.stream()
                .filter(s -> s.date() == null || s.date().compareTo(today) >= 0)
                .toList();
        if (upcomingStubs.isEmpty()) {
            log.info("    -> no upcoming featured events on calendar page");
            return List.of();
        }

        List<Event> enriched = new ArrayList<>();
        for (Event stub : upcomingStubs) {
            String eventUrl = stub.lumaUrl();
            boolean fromCalendar = "luma_calendar_nextdata".equals(stub.parseMethod());
            if (fromCalendar && eventUrl != null && !eventUrl.equals(url)) {
                log.info("    -> fetching event page: {}", eventUrl);
                List<Event> detail = fetchEvent(eventUrl);
                if (!detail.isEmpty()) {
                    enriched.addAll(detail);
                    continue;
                }
            }
            enriched.add(stub);
        }

        String source = "luma_calendar:" + slugOrUrl;
        String fetchedAt = Instant.now().toString();
        return enriched.stream()
                .map(e -> e.enriched(e.title(), null, null, source, fetchedAt))
                .toList();
    }

    private static String fetchHtml(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", Http.USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LumaFetchException("luma_http_" + resp.statusCode(),
                        "fetch " + url + " returned HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (IOException e) {
            throw new LumaFetchException("luma_io",
                    "fetch " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LumaFetchException("luma_interrupted", "fetch " + url + " interrupted");
        }
    }
}
