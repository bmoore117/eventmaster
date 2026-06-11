package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.InstagramPost;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramPostClassifierTest {

    @Test
    void buildUserPrompt_includesCurrentDateAndPostedDate() throws Exception {
        InstagramPost post = new InstagramPost(
                "111",
                "beachrepublicans",
                "ABC123",
                "This Thursday at 6pm",
                "https://www.instagram.com/p/ABC123/",
                "2026-05-19T22:00:00Z",
                "photo",
                "2026-05-23T12:01:00Z"
        );

        String prompt = InstagramPostClassifier.buildUserPrompt(
                List.of(post), Map.of(), LocalDate.of(2026, 5, 23));

        assertTrue(prompt.contains("\"currentDate\" : \"2026-05-23\""));
        assertTrue(prompt.contains("\"postedDate\" : \"2026-05-19\""));
    }

    @Test
    void buildUserPrompt_assignsSequentialIdsToUpcomingEvents() throws Exception {
        Event first = new Event("Beach Bash", "2026-05-30", "18:00",
                null, null, "South Pointe Park", null,
                "https://lu.ma/beach-bash", "luma_jsonld", null, null, null, null, null);
        Event second = new Event("Open House", "2026-06-01", "10:00",
                null, null, "Adam's Studio", null,
                null, "ics", null, null, null, null, null);

        Map<String, Event> indexed = InstagramPostClassifier.indexUpcoming(List.of(first, second));
        String prompt = InstagramPostClassifier.buildUserPrompt(
                List.of(), indexed, LocalDate.of(2026, 5, 23));

        assertEquals(first, indexed.get("u0"));
        assertEquals(second, indexed.get("u1"));
        assertTrue(prompt.contains("\"id\" : \"u0\""));
        assertTrue(prompt.contains("\"id\" : \"u1\""));
        assertTrue(prompt.contains("\"title\" : \"Beach Bash\""));
        assertTrue(prompt.contains("\"title\" : \"Open House\""));
    }

    @Test
    void parseEvents_extractsEventPostsAndSkipsNonEvents() throws Exception {
        InstagramPost eventPost = new InstagramPost(
                "111",
                "beachrepublicans",
                "ABC123",
                "Join us Friday at 6pm at the pier",
                "https://www.instagram.com/p/ABC123/",
                "2026-05-23T12:00:00Z",
                "photo",
                "2026-05-23T12:01:00Z"
        );
        InstagramPost otherPost = new InstagramPost(
                "222",
                "beachrepublicans",
                "DEF456",
                "Great turnout last week",
                "https://www.instagram.com/p/DEF456/",
                "2026-05-22T12:00:00Z",
                "photo",
                "2026-05-23T12:01:00Z"
        );

        String response = """
                ```json
                {
                  "posts": [
                    {
                      "id": "111",
                      "isEvent": true,
                      "title": "Pier meetup",
                      "date": "2026-05-30",
                      "time": "18:00",
                      "location": "The pier",
                      "description": "Join us Friday at 6pm at the pier",
                      "matchedUpcomingEventId": null
                    },
                    {
                      "id": "222",
                      "isEvent": false,
                      "reason": "Recap, not an announcement"
                    }
                  ]
                }
                ```
                """;

        List<Event> events = InstagramPostClassifier.parseEvents(
                response, List.of(eventPost, otherPost), Map.of());

        assertEquals(1, events.size());
        Event event = events.getFirst();
        assertEquals("Pier meetup", event.title());
        assertEquals("2026-05-30", event.date());
        assertEquals("18:00", event.time());
        assertEquals("instagram_agent", event.parseMethod());
        assertEquals("instagram:beachrepublicans", event.source());
        assertEquals("111", event.sourceMessageId());
        assertTrue(event.lumaUrl().endsWith("/p/ABC123/"));
    }

    @Test
    void parseEvents_skipsPostMatchedToExistingUpcomingEvent() throws Exception {
        InstagramPost post = new InstagramPost(
                "111",
                "beachrepublicans",
                "ABC123",
                "We're back at Adam's tonight, see you there",
                "https://www.instagram.com/p/ABC123/",
                "2026-05-23T12:00:00Z",
                "photo",
                "2026-05-23T12:01:00Z"
        );
        Event existing = new Event("Open House", "2026-05-23", "19:00",
                null, null, "Adam's Studio", null,
                null, "ics", null, null, null, null, null);
        Map<String, Event> upcoming = InstagramPostClassifier.indexUpcoming(List.of(existing));

        String response = """
                {
                  "posts": [
                    {
                      "id": "111",
                      "isEvent": true,
                      "title": "Open House at Adam's",
                      "date": "2026-05-23",
                      "matchedUpcomingEventId": "u0"
                    }
                  ]
                }
                """;

        List<Event> events = InstagramPostClassifier.parseEvents(
                response, List.of(post), upcoming);

        assertEquals(0, events.size(),
                "Matched posts should not produce a separate Event — the existing upcoming event already represents them");
    }

    @Test
    void parseEvents_treatsUnknownMatchIdAsUnmatched() throws Exception {
        InstagramPost post = new InstagramPost(
                "111",
                "beachrepublicans",
                "ABC123",
                "Block party Saturday!",
                "https://www.instagram.com/p/ABC123/",
                "2026-05-23T12:00:00Z",
                "photo",
                "2026-05-23T12:01:00Z"
        );

        String response = """
                {
                  "posts": [
                    {
                      "id": "111",
                      "isEvent": true,
                      "title": "Block party",
                      "date": "2026-05-30",
                      "matchedUpcomingEventId": "u99"
                    }
                  ]
                }
                """;

        List<Event> events = InstagramPostClassifier.parseEvents(
                response, List.of(post), Map.of());

        assertEquals(1, events.size(),
                "Unknown match ids should be treated as unmatched (hallucination guard), and the event still emitted");
        assertEquals("Block party", events.getFirst().title());
    }
}
