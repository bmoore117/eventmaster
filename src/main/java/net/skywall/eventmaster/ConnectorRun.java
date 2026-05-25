package net.skywall.eventmaster;

import net.skywall.eventmaster.model.EmailMessage;
import net.skywall.eventmaster.model.Event;
import net.skywall.eventmaster.model.Health;
import net.skywall.eventmaster.model.InstagramPost;
import net.skywall.eventmaster.model.RunWarning;
import net.skywall.eventmaster.utils.DateFilters;
import net.skywall.eventmaster.utils.EmailParser;
import net.skywall.eventmaster.utils.GmailSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One full connector run: read Gmail + Luma calendars + Instagram posts, dedup,
 * classify, persist, and notify Hermes when there's something new or an error
 * to report. Source-level failures become {@link RunWarning}s that don't block
 * other sources or the events webhook; their delivery is a separate, best-effort
 * webhook call gated on the warning set having changed since the previous run.
 */
public final class ConnectorRun {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRun.class);

    private final Config config;
    private final EventStore eventStore;
    private final StateStore stateStore;
    private final HermesClient hermes;
    private final InstagramPostClassifier instagramClassifier;

    public ConnectorRun(Config config) {
        this.config = config;
        this.eventStore = new EventStore(config.upcomingEventsPath, config.pastEventsPath);
        this.stateStore = new StateStore(config.processedIdsPath, config.connectorStatePath);
        this.hermes = new HermesClient(config);
        this.instagramClassifier = new InstagramPostClassifier(config);
    }

    /** Run the connector. Returns 0 on success, 1 on failure (matches the Python exit codes). */
    public int execute() {
        log.info("--- email-connector run started ---");

        Set<String> processedIds = stateStore.loadProcessedIds();
        Set<String> instagramBootstrapped = new HashSet<>(stateStore.loadInstagramBootstrapped());
        Set<String> lastWarningCodes = stateStore.loadLastWarningCodes();
        List<Event> upcoming = eventStore.loadUpcoming();
        List<Event> past = eventStore.loadPast();
        List<Event> newEvents = new ArrayList<>();
        List<RunWarning> warnings = new ArrayList<>();
        int emailsProcessed = 0;

        try {
            emailsProcessed = processGmailSafely(processedIds, newEvents, warnings);
            processLumaCalendarsSafely(newEvents, warnings);
            processInstagramSafely(processedIds, instagramBootstrapped, upcoming, newEvents, warnings);

            EventStore.RotateResult rotated = eventStore.rotateAndClassify(upcoming, past, newEvents);
            List<Event> nextUpcoming = rotated.upcoming();
            List<Event> nextPast = rotated.past();
            List<Event> newlyAdded = rotated.newlyAdded();

            log.info("Run complete: {} email(s) processed, {} event(s) parsed, "
                            + "{} newly upcoming, {} upcoming total, {} past total, {} warning(s)",
                    emailsProcessed, newEvents.size(), newlyAdded.size(),
                    nextUpcoming.size(), nextPast.size(), warnings.size());

            String now = Instant.now().toString();
            Health health = new Health(now, emailsProcessed,
                    nextUpcoming.size(), nextPast.size(),
                    0, null, null);

            // 1) Events webhook — ATOMIC. If delivery fails we throw so the
            //    catch block treats it as a run failure: processedIds,
            //    instagramBootstrapped, and the new events stay unsaved and
            //    the next run will re-scrape and retry the same notification.
            if (newlyAdded.isEmpty()) {
                log.info("No new events — events webhook not sent");
            } else if (!hermes.notifyEvents(now, newlyAdded, health)) {
                throw new WebhookDeliveryException(
                        "Hermes events webhook delivery failed for " + newlyAdded.size() + " new event(s)");
            }

            // 2) Warnings webhook — BEST-EFFORT. Only fires when the warning
            //    set has changed since the previous run (add, remove, or
            //    change), so we don't spam the agent every cron tick while a
            //    source is degraded. If delivery fails, leave
            //    last_warning_codes alone so next run retries the same diff.
            WarningDiff diff = WarningDiff.compute(warnings, lastWarningCodes);
            boolean warningsDelivered = true;
            if (diff.changed()) {
                log.info("Warnings changed since last run — sending warnings webhook ({} current, {} resolved)",
                        diff.currentSorted().size(), diff.resolved().size());
                warningsDelivered = hermes.notifyWarnings(now, diff.currentSorted(), diff.resolved());
                if (!warningsDelivered) {
                    log.warn("Warnings webhook delivery failed — last_warning_codes left unchanged, will retry next run");
                }
            }

            // 3) Commit state. last_warning_codes only advances if its
            //    delivery succeeded (or the diff was empty in the first
            //    place); everything else commits unconditionally.
            stateStore.saveProcessedIds(processedIds);
            stateStore.saveInstagramBootstrapped(instagramBootstrapped);
            eventStore.writeUpcoming(nextUpcoming);
            eventStore.writePast(nextPast);
            stateStore.saveConsecutiveFailures(0);
            if (warningsDelivered) {
                stateStore.saveLastWarningCodes(diff.currentCodes());
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

            // Don't re-hammer the webhook with an error notification if we
            // just failed to deliver one — the next run will retry the real
            // payload anyway.
            if (e instanceof WebhookDeliveryException) {
                log.warn("Skipping Hermes error notification — webhook itself is the failure mode");
            } else {
                hermes.notifyEvents(now, List.of(), errorHealth);
            }
            return 1;
        }
    }

    /** Sentinel exception: success-path events webhook delivery failed. */
    private static final class WebhookDeliveryException extends RuntimeException {
        WebhookDeliveryException(String message) {
            super(message);
        }
    }

    private int processGmailSafely(
            Set<String> processedIds, List<Event> newEvents, List<RunWarning> warnings
    ) {
        try {
            return processGmail(processedIds, newEvents);
        } catch (Exception e) {
            // Phase-level catch: any successfully parsed messages before the
            // throw are already in newEvents / processedIds; the failing
            // message stays unprocessed and retries next run.
            String code = classifyGmailError(e);
            log.error("Gmail phase failed ({}): {}", code, e.getMessage(), e);
            warnings.add(new RunWarning("gmail", code, safeMessage(e)));
            return 0;
        }
    }

    private static String classifyGmailError(Throwable e) {
        String name = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (name.contains("Authentication") || message.contains("authentication")) {
            return "gmail_auth_failed";
        }
        if (name.contains("Connect") || message.contains("connect")) {
            return "gmail_connect_failed";
        }
        if (message.contains("label not found")) {
            return "gmail_label_missing";
        }
        return "gmail_error";
    }

    private void processLumaCalendarsSafely(List<Event> newEvents, List<RunWarning> warnings) {
        for (String slug : config.lumaCalendars()) {
            log.info("Fetching from Luma calendar: {}", slug);
            try {
                List<Event> events = LumaScraper.fetchCalendar(slug);
                if (!events.isEmpty()) {
                    newEvents.addAll(events);
                    log.info("  -> extracted {} event(s) via {}", events.size(), events.getFirst().parseMethod());
                }
            } catch (LumaFetchException e) {
                log.error("Luma calendar {} failed ({}): {}", slug, e.code(), e.getMessage());
                warnings.add(new RunWarning("luma:" + slug, e.code(), safeMessage(e)));
            }
        }
    }

    private void processInstagramSafely(
            Set<String> processedIds,
            Set<String> bootstrappedAccounts,
            List<Event> upcomingEvents,
            List<Event> newEvents,
            List<RunWarning> warnings
    ) {
        if (!config.instagramEnabled()) {
            return;
        }

        ScrapeCreatorsClient client = new ScrapeCreatorsClient(config.scrapeCreatorsApiKey());
        List<InstagramPost> postsToClassify = new ArrayList<>();

        for (String handle : config.instagramAccounts()) {
            log.info("Fetching Instagram posts for @{}", handle);
            List<InstagramPost> posts;
            try {
                posts = client.fetchUserPosts(handle);
            } catch (ScrapeCreatorsException e) {
                log.error("ScrapeCreators failed for @{} ({}): {}", handle, e.code(), e.getMessage());
                warnings.add(new RunWarning("instagram:" + handle, e.code(), safeMessage(e)));
                continue;
            }
            if (posts.isEmpty()) {
                continue;
            }

            boolean bootstrapped = bootstrappedAccounts.contains(handle);
            int unseen = 0;

            for (InstagramPost post : posts) {
                if (processedIds.contains(post.id())) {
                    continue;
                }

                if (bootstrapped) {
                    postsToClassify.add(post);
                } else {
                    processedIds.add(post.id());
                    unseen++;
                }
            }

            if (!bootstrapped) {
                bootstrappedAccounts.add(handle);
                log.info("  -> bootstrapped @{} — {} post(s) marked seen, none classified",
                        handle, unseen);
            }
        }

        if (postsToClassify.isEmpty()) {
            return;
        }

        // Hand the classifier this run's freshly-scraped Gmail/Luma events in
        // addition to the on-disk upcoming list, so an Instagram post about an
        // event we just discovered in the same run can still resolve to the
        // matching lumaUrl (and dedup with it in EventStore.rotateAndClassify)
        // instead of falling back to the post's permalink.
        List<Event> contextEvents = new ArrayList<>(upcomingEvents);
        for (Event e : newEvents) {
            if (!DateFilters.isPast(e)) {
                contextEvents.add(e);
            }
        }

        // Collapse hint-set duplicates before the LLM call. The on-disk
        // upcoming list and a re-scrape of the same Luma calendar virtually
        // always overlap, and we'd rather not pay for those copies in tokens
        // (or risk the LLM picking a redundant entry to match against).
        int rawContextSize = contextEvents.size();
        contextEvents = EventStore.dedupByHints(contextEvents);
        if (contextEvents.size() < rawContextSize) {
            log.info("Deduped classifier context: {} -> {} event(s)",
                    rawContextSize, contextEvents.size());
        }

        log.info("Classifying {} Instagram post(s) via Hermes API", postsToClassify.size());
        try {
            List<Event> extracted = instagramClassifier.classifyPosts(postsToClassify, contextEvents);
            if (!extracted.isEmpty()) {
                newEvents.addAll(extracted);
                log.info("  -> added {} event(s) from Instagram (instagram_agent)", extracted.size());
            }

            for (InstagramPost post : postsToClassify) {
                processedIds.add(post.id());
            }
        } catch (IOException | JacksonException e) {
            // Classifier-level soft failure: leave the post IDs unprocessed
            // so the next run retries them, and emit a warning so the agent
            // sees the degradation.
            log.error("Instagram classification failed — {} post(s) left unprocessed for retry: {}",
                    postsToClassify.size(), e.getMessage());
            String code = e instanceof JacksonException ? "classifier_malformed_json" : "classifier_io";
            warnings.add(new RunWarning("instagram_classifier", code, safeMessage(e)));
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
                log.info("  -> extracted {} event(s) via {}", events.size(), events.getFirst().parseMethod());
            } else {
                log.info("  -> no events found in this email");
            }
            processedIds.add(msgId);
            emailsProcessed++;
        }
        return emailsProcessed;
    }

    private static String safeMessage(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
