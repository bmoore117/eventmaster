package net.skywall.eventmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    private static StateStore newStore(Path dir) {
        return new StateStore(
                dir.resolve("processed_ids.txt"),
                dir.resolve("connector-state.json"));
    }

    @Test
    void loadLastWarningCodes_returnsEmptyForFreshState(@TempDir Path dir) {
        StateStore store = newStore(dir);
        assertTrue(store.loadLastWarningCodes().isEmpty(),
                "Brand-new connector-state.json has no recorded warnings");
    }

    @Test
    void saveAndLoadLastWarningCodes_roundtrips(@TempDir Path dir) throws IOException {
        StateStore store = newStore(dir);
        Set<String> codes = Set.of(
                "instagram:beachrepublicans|scrapecreators_402",
                "luma:FTLYR|luma_http_502");

        store.saveLastWarningCodes(codes);

        assertEquals(codes, store.loadLastWarningCodes());
    }

    @Test
    void saveLastWarningCodes_preservesOtherFields(@TempDir Path dir) throws IOException {
        StateStore store = newStore(dir);
        store.saveConsecutiveFailures(3);
        store.saveInstagramBootstrapped(Set.of("alpha", "beta"));

        store.saveLastWarningCodes(Set.of("gmail|gmail_auth_failed"));

        assertEquals(3, store.loadConsecutiveFailures(),
                "Saving warning codes must not zero out consecutive_failures");
        assertEquals(Set.of("alpha", "beta"), store.loadInstagramBootstrapped(),
                "Saving warning codes must not erase instagram_bootstrapped");
        assertEquals(Set.of("gmail|gmail_auth_failed"), store.loadLastWarningCodes());
    }

    @Test
    void saveOtherFields_preservesLastWarningCodes(@TempDir Path dir) throws IOException {
        StateStore store = newStore(dir);
        store.saveLastWarningCodes(Set.of("gmail|gmail_auth_failed"));

        store.saveConsecutiveFailures(1);
        store.saveInstagramBootstrapped(Set.of("alpha"));

        assertEquals(Set.of("gmail|gmail_auth_failed"), store.loadLastWarningCodes(),
                "Independent writes to other state fields must preserve last_warning_codes");
    }

    @Test
    void saveLastWarningCodes_emptySetClearsField(@TempDir Path dir) throws IOException {
        StateStore store = newStore(dir);
        store.saveLastWarningCodes(Set.of("gmail|gmail_auth_failed"));
        store.saveLastWarningCodes(Set.of());

        assertTrue(store.loadLastWarningCodes().isEmpty(),
                "Empty save must clear the previously recorded codes");
    }

    @Test
    void loadLastWarningCodes_skipsBlankEntriesInFile(@TempDir Path dir) throws IOException {
        StateStore store = newStore(dir);
        Files.writeString(dir.resolve("connector-state.json"),
                "{\n  \"consecutive_failures\": 0,\n  \"last_warning_codes\": [\"a|b\", \"\", \"c|d\"]\n}\n");

        assertEquals(Set.of("a|b", "c|d"), store.loadLastWarningCodes(),
                "Blank string entries in the persisted array must be ignored");
    }
}
