package net.skywall.eventmaster;

import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.Health;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One full connector run: read Gmail + Luma calendars, dedup, classify, persist,
 * and notify Hermes when there's something new or an error to report.
 *
 * <p>This is the Java port of the {@code main()} function in
 * {@code fetch_events.py}. The success and failure code paths each finish by
 * stamping a {@link Health} snapshot and (when warranted) POSTing it to Hermes.
 */
public final class ConnectorRun {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRun.class);

    private final Config config;
    private final EventStore eventStore;
    private final StateStore stateStore;
    private final HermesClient hermes;

    public ConnectorRun(Config config) {
        this.config = config;
        this.eventStore = new EventStore(config.upcomingEventsPath, config.pastEventsPath);
        this.stateStore = new StateStore(config.processedIdsPath, config.connectorStatePath);
        this.hermes = new HermesClient(config);
    }

    /** Run the connector. Returns 0 on success, 1 on failure (matches the Python exit codes). */
    public int execute() {
        log.info("--- email-connector run started ---");

        Set<String> processedIds = stateStore.loadProcessedIds();
        List<Event> upcoming = eventStore.loadUpcoming();
        List<Event> past = eventStore.loadPast();
        List<Event> newEvents = new ArrayList<>();
        int emailsProcessed = 0;

        try {
            emailsProcessed = processGmail(processedIds, newEvents);
            processLumaCalendars(newEvents);

            stateStore.saveProcessedIds(processedIds);

            EventStore.RotateResult rotated =
                    eventStore.rotateAndClassify(upcoming, past, newEvents);
            upcoming = rotated.upcoming();
            past = rotated.past();
            List<Event> newlyAdded = rotated.newlyAdded();

            eventStore.writeUpcoming(upcoming);
            eventStore.writePast(past);

            log.info("Run complete: {} email(s) processed, {} event(s) parsed, "
                            + "{} newly upcoming, {} upcoming total, {} past total",
                    emailsProcessed, newEvents.size(), newlyAdded.size(),
                    upcoming.size(), past.size());

            String now = Instant.now().toString();
            Health health = new Health(now, emailsProcessed,
                    upcoming.size(), past.size(),
                    0, null, null);

            stateStore.saveConsecutiveFailures(0);

            if (!newlyAdded.isEmpty()) {
                hermes.notify(now, newlyAdded, health);
            } else {
                log.info("No new events — Hermes webhook not sent");
            }
            return 0;

        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);

            int prevFailures = stateStore.loadConsecutiveFailures();
            String now = Instant.now().toString();
            Health errorHealth = new Health(now, emailsProcessed,
                    upcoming.size(), past.size(),
                    prevFailures + 1, e.getMessage(), now);

            try {
                stateStore.saveConsecutiveFailures(errorHealth.consecutiveFailures());
            } catch (IOException io) {
                log.warn("Could not save connector state: {}", io.getMessage());
            }
            hermes.notify(now, List.of(), errorHealth);
            return 1;
        }
    }

    private int processGmail(Set<String> processedIds, List<Event> newEvents) throws Exception {
        List<EmailMessage> messages = GmailSource.readLabel(
                config.gmailUser(), config.gmailAppPassword(), config.gmailLabel());

        int emailsProcessed = 0;
        for (EmailMessage msg : messages) {
            String msgId = msg.messageId();
            if (processedIds.contains(msgId)) {
                continue;
            }

            log.info("Processing: [{}] from {}", msg.subject(), msg.from());
            List<Event> events = EmailParser.parse(msg);
            if (!events.isEmpty()) {
                newEvents.addAll(events);
                log.info("  -> extracted {} event(s) via {}",
                        events.size(), events.getFirst().parseMethod());
            } else {
                log.info("  -> no events found in this email");
            }
            processedIds.add(msgId);
            emailsProcessed++;
        }
        return emailsProcessed;
    }

    private void processLumaCalendars(List<Event> newEvents) {
        for (String slug : config.lumaCalendars()) {
            log.info("Fetching from Luma calendar: {}", slug);
            List<Event> events = LumaScraper.fetchCalendar(slug);
            if (!events.isEmpty()) {
                newEvents.addAll(events);
                log.info("  -> extracted {} event(s) via {}",
                        events.size(), events.getFirst().parseMethod());
            }
        }
    }
}
