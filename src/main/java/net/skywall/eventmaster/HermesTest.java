package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.Health;
import net.skywall.eventmaster.model.WebhookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Builds synthetic Hermes webhook payloads for end-to-end verification of the
 * Hermes route without touching Gmail or Luma. Mirrors
 * {@code _sample_test_events}, {@code _sample_test_health}, and
 * {@code run_hermes_test} from {@code fetch_events.py}.
 */
public final class HermesTest {

    private static final Logger log = LoggerFactory.getLogger(HermesTest.class);

    private HermesTest() {}

    public static int run(HermesClient client, boolean simulateError, boolean dryRun) {
        log.info("--- Hermes webhook test (error={}, dry_run={}) ---", simulateError, dryRun);
        String url = client.webhook().url();
        log.info("Target: {}", (url == null || url.isBlank()) ? "(not configured)" : url);

        List<Event> events = simulateError ? List.of() : sampleEvents();
        Health health = sampleHealth(simulateError);
        String triggeredAt = Instant.now().toString();

        WebhookPayload payload;
        try {
            payload = client.buildPayload(triggeredAt, events, health);
        } catch (IOException e) {
            log.error("{}", e.getMessage());
            return 1;
        }

        if (dryRun) {
            try {
                System.out.println(Json.PRETTY.writeValueAsString(payload));
            } catch (IOException e) {
                log.error("Could not serialise payload: {}", e.getMessage());
                return 1;
            }
            log.info("Dry run — payload printed, not sent (hasErrors={})", payload.hasErrors());
            return 0;
        }

        if (client.post(payload)) {
            log.info("Test webhook succeeded (hasErrors={})", payload.hasErrors());
            return 0;
        }

        return 1;
    }

    private static List<Event> sampleEvents() {
        String eventDate = LocalDate.now().plusDays(3).toString();
        return List.of(new Event(
                "[TEST] Miami Social Radar \u2014 webhook check",
                eventDate,
                "19:00",
                null,
                null,
                "Test venue (synthetic \u2014 safe to ignore)",
                "Synthetic event from `fetch_events.py test`.",
                "https://lu.ma/test-webhook-check",
                "test",
                "fetch_events.py test",
                null,
                null,
                Instant.now().toString()
        ));
    }

    private static Health sampleHealth(boolean simulateError) {
        String now = Instant.now().toString();
        if (simulateError) {
            return new Health(now, 0, 0, 0, 1,
                    "Synthetic error from fetch_events.py test --error", now);
        }
        return new Health(now, 0, 1, 0, 0, null, null);
    }
}
