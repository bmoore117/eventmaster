package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.InstagramPost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramPostClassifierTest {

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
                      "matchedUpcomingEventUrl": null
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

        List<Event> events = InstagramPostClassifier.parseEvents(response, List.of(eventPost, otherPost));

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
}
