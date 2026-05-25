package net.skywall.eventmaster;

import net.skywall.eventmaster.model.RunWarning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The difference between this run's warning set and the persisted set from the
 * previous run. Drives the "should we fire the warnings webhook?" decision in
 * {@link ConnectorRun}.
 *
 * <ul>
 *   <li>{@link #currentCodes()} — dedup keys ({@code source|code}) firing
 *       this run.
 *   <li>{@link #currentSorted()} — full {@link RunWarning} list sorted by
 *       dedup key for stable agent-side rendering.
 *   <li>{@link #resolved()} — dedup keys that were present in the previous
 *       run but are no longer firing (sorted alphabetically).
 *   <li>{@link #changed()} — true iff the current set differs from the
 *       previous set; the webhook only fires when this is true.
 * </ul>
 */
public record WarningDiff(
        Set<String> currentCodes,
        List<RunWarning> currentSorted,
        List<String> resolved,
        boolean changed
) {

    private static final String PRESERVED_MESSAGE =
            "Not re-checked this run — Instagram fetch skipped (global throttle)";

    public static WarningDiff compute(List<RunWarning> current, Set<String> previous) {
        return compute(current, previous, false);
    }

    /**
     * @param preserveUncheckedInstagramWarnings when {@code true}, any
     *        Instagram-related codes present in {@code previous} but absent
     *        from {@code current} are carried forward rather than reported as
     *        resolved. Used when the Instagram phase was skipped due to the
     *        global fetch interval — we must not treat "didn't check" as
     *        "recovered".
     */
    public static WarningDiff compute(
            List<RunWarning> current,
            Set<String> previous,
            boolean preserveUncheckedInstagramWarnings
    ) {
        List<RunWarning> effective = new ArrayList<>(current);
        if (preserveUncheckedInstagramWarnings) {
            Set<String> observedKeys = new HashSet<>();
            for (RunWarning w : current) {
                observedKeys.add(w.dedupKey());
            }
            for (String prevKey : previous) {
                if (isInstagramRelated(prevKey) && !observedKeys.contains(prevKey)) {
                    effective.add(fromDedupKey(prevKey, PRESERVED_MESSAGE));
                    observedKeys.add(prevKey);
                }
            }
        }

        Set<String> currentCodes = new HashSet<>();
        for (RunWarning w : effective) {
            currentCodes.add(w.dedupKey());
        }

        List<RunWarning> sorted = new ArrayList<>(effective);
        sorted.sort(Comparator.comparing(RunWarning::dedupKey));

        List<String> resolved = previous.stream()
                .filter(c -> !currentCodes.contains(c))
                .sorted()
                .toList();

        boolean changed = !currentCodes.equals(previous);
        return new WarningDiff(currentCodes, sorted, resolved, changed);
    }

    static boolean isInstagramRelated(String dedupKey) {
        int pipe = dedupKey.indexOf('|');
        if (pipe < 0) {
            return false;
        }
        String source = dedupKey.substring(0, pipe);
        return source.equals("instagram_classifier") || source.startsWith("instagram:");
    }

    static RunWarning fromDedupKey(String dedupKey, String message) {
        int pipe = dedupKey.indexOf('|');
        if (pipe < 0) {
            throw new IllegalArgumentException("Invalid dedup key: " + dedupKey);
        }
        return new RunWarning(dedupKey.substring(0, pipe), dedupKey.substring(pipe + 1), message);
    }
}
