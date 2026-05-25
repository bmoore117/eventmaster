package net.skywall.eventmaster;

import net.skywall.eventmaster.model.RunWarning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarningDiffTest {

    private static RunWarning w(String source, String code) {
        return new RunWarning(source, code, "test message for " + source + "/" + code);
    }

    @Test
    void compute_emptyToEmptyIsNotChanged() {
        WarningDiff diff = WarningDiff.compute(List.of(), Set.of());

        assertFalse(diff.changed());
        assertTrue(diff.currentCodes().isEmpty());
        assertTrue(diff.resolved().isEmpty());
    }

    @Test
    void compute_addingFirstWarningIsChanged() {
        WarningDiff diff = WarningDiff.compute(
                List.of(w("instagram:beachrepublicans", "scrapecreators_402")),
                Set.of());

        assertTrue(diff.changed());
        assertEquals(Set.of("instagram:beachrepublicans|scrapecreators_402"), diff.currentCodes());
        assertTrue(diff.resolved().isEmpty());
        assertEquals(1, diff.currentSorted().size());
    }

    @Test
    void compute_sameWarningSetIsNotChanged() {
        WarningDiff diff = WarningDiff.compute(
                List.of(w("instagram:beachrepublicans", "scrapecreators_402")),
                Set.of("instagram:beachrepublicans|scrapecreators_402"));

        assertFalse(diff.changed(),
                "Identical warning set across runs must not refire the webhook");
        assertTrue(diff.resolved().isEmpty());
    }

    @Test
    void compute_resolvedWarningIsChangedWithResolvedList() {
        WarningDiff diff = WarningDiff.compute(
                List.of(),
                Set.of("instagram:beachrepublicans|scrapecreators_402"));

        assertTrue(diff.changed());
        assertTrue(diff.currentCodes().isEmpty());
        assertEquals(
                List.of("instagram:beachrepublicans|scrapecreators_402"),
                diff.resolved(),
                "Previously firing warning that's no longer firing is reported as resolved");
    }

    @Test
    void compute_addedAndResolvedInSameRun() {
        WarningDiff diff = WarningDiff.compute(
                List.of(w("gmail", "gmail_connect_failed")),
                Set.of("instagram:beachrepublicans|scrapecreators_402"));

        assertTrue(diff.changed());
        assertEquals(Set.of("gmail|gmail_connect_failed"), diff.currentCodes());
        assertEquals(List.of("instagram:beachrepublicans|scrapecreators_402"), diff.resolved());
    }

    @Test
    void compute_currentSortedIsStableByDedupKey() {
        WarningDiff diff = WarningDiff.compute(
                List.of(
                        w("luma:zeta", "luma_http_500"),
                        w("gmail", "gmail_connect_failed"),
                        w("instagram:alpha", "scrapecreators_402")),
                Set.of());

        List<String> keys = diff.currentSorted().stream().map(RunWarning::dedupKey).toList();
        assertEquals(List.of(
                "gmail|gmail_connect_failed",
                "instagram:alpha|scrapecreators_402",
                "luma:zeta|luma_http_500"
        ), keys, "currentSorted must be deterministic so the agent sees stable ordering");
    }

    @Test
    void compute_codeChangeForSameSourceCountsAsChanged() {
        WarningDiff diff = WarningDiff.compute(
                List.of(w("instagram:beachrepublicans", "scrapecreators_500")),
                Set.of("instagram:beachrepublicans|scrapecreators_402"));

        assertTrue(diff.changed(),
                "Same source with a different error code is a new diagnostic signal");
        assertEquals(List.of("instagram:beachrepublicans|scrapecreators_402"), diff.resolved());
        assertEquals(Set.of("instagram:beachrepublicans|scrapecreators_500"), diff.currentCodes());
    }
}
