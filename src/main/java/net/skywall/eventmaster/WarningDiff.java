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

    public static WarningDiff compute(List<RunWarning> current, Set<String> previous) {
        Set<String> currentCodes = new HashSet<>();
        for (RunWarning w : current) {
            currentCodes.add(w.dedupKey());
        }

        List<RunWarning> sorted = new ArrayList<>(current);
        sorted.sort(Comparator.comparing(RunWarning::dedupKey));

        List<String> resolved = previous.stream()
                .filter(c -> !currentCodes.contains(c))
                .sorted()
                .toList();

        boolean changed = !currentCodes.equals(previous);
        return new WarningDiff(currentCodes, sorted, resolved, changed);
    }
}
