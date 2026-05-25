package net.skywall.eventmaster.model;

/**
 * A non-fatal degradation observed during a connector run. Emitted by each
 * source phase ({@code processGmail}, {@code processLumaCalendars},
 * {@code processInstagram}) when a fetch or parse step fails in a way that
 * doesn't justify aborting the whole run.
 *
 * <p>{@code source} is the failing component (e.g. {@code "gmail"},
 * {@code "luma:FTLYR"}, {@code "instagram:beachrepublicans"}).
 * {@code code} is a short machine-readable tag (e.g. {@code "scrapecreators_402"},
 * {@code "imap_connect_failed"}) used for the warning-diff dedup across runs.
 * {@code message} is a human-readable description for the agent and the log.
 *
 * <p>Two warnings with the same {@code source + code} are considered identical
 * for purposes of the "warnings changed since last run" check that gates the
 * warnings webhook.
 */
public record RunWarning(String source, String code, String message) {

    /** Canonical string used for the warning-diff set: "source|code". */
    public String dedupKey() {
        return source + "|" + code;
    }
}
