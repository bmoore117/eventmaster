package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.Health;
import net.skywall.eventmaster.model.RunWarning;
import net.skywall.eventmaster.model.WarningsPayload;
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
        log.info("--- Hermes events webhook test (error={}, dry_run={}) ---", simulateError, dryRun);
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
            System.out.println(Json.PRETTY.writeValueAsString(payload));
            log.info("Dry run - payload printed, not sent (hasErrors={})", payload.hasErrors());
            return 0;
        }

        if (client.post(payload)) {
            log.info("Test webhook succeeded (hasErrors={})", payload.hasErrors());
            return 0;
        }

        return 1;
    }

    public static int runWarnings(HermesClient client, boolean dryRun) {
        log.info("--- Hermes warnings webhook test (dry_run={}) ---", dryRun);
        String url = client.webhook().url();
        log.info("Target: {}", (url == null || url.isBlank()) ? "(not configured)" : url);

        List<RunWarning> current = sampleCurrentWarnings();
        List<String> resolved = sampleResolvedWarnings();
        String triggeredAt = Instant.now().toString();

        WarningsPayload payload;
        try {
            payload = client.buildWarningsPayload(triggeredAt, current, resolved);
        } catch (IOException e) {
            log.error("{}", e.getMessage());
            return 1;
        }

        if (dryRun) {
            System.out.println(Json.PRETTY.writeValueAsString(payload));
            log.info("Dry run - warnings payload printed, not sent ({} current, {} resolved)",
                    current.size(), resolved.size());
            return 0;
        }

        if (client.post(payload)) {
            log.info("Test warnings webhook succeeded ({} current, {} resolved)",
                    current.size(), resolved.size());
            return 0;
        }

        return 1;
    }

    private static List<Event> sampleEvents() {
        String eventDate = LocalDate.now().plusDays(3).toString();
        return List.of(new Event(
                "[TEST] Miami Social Radar - webhook check",
                eventDate,
                "19:00",
                null,
                null,
                "Test venue (synthetic - safe to ignore)",
                "Synthetic event from `fetch_events.py test`.",
                "https://lu.ma/test-webhook-check",
                "test",
                "fetch_events.py test",
                null,
                null,
                Instant.now().toString(),
                null
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

    private static List<RunWarning> sampleCurrentWarnings() {
        return List.of(
                new RunWarning("instagram:beachrepublicans", "scrapecreators_402",
                        "[TEST] Synthetic: rejected @beachrepublicans (402): out of credits"),
                new RunWarning("luma:FTLYR", "luma_http_502",
                        "[TEST] Synthetic: fetch https://lu.ma/FTLYR returned HTTP 502"));
    }

    private static List<String> sampleResolvedWarnings() {
        return List.of("gmail|gmail_connect_failed");
    }
}
