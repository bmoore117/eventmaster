package net.skywall.eventmaster;

import net.skywall.eventmaster.model.RunWarning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RunWarningTest {

    @Test
    void dedupKey_joinsSourceAndCodeWithPipe() {
        RunWarning w = new RunWarning("instagram:beachrepublicans", "scrapecreators_402", "out of credits");
        assertEquals("instagram:beachrepublicans|scrapecreators_402", w.dedupKey());
    }

    @Test
    void dedupKey_messageDoesNotInfluenceDedup() {
        RunWarning a = new RunWarning("gmail", "gmail_connect_failed", "Connection refused at 22:30");
        RunWarning b = new RunWarning("gmail", "gmail_connect_failed", "Connection refused at 22:35");
        assertEquals(a.dedupKey(), b.dedupKey(),
                "Two warnings with the same source+code must dedup regardless of message text");
    }

    @Test
    void dedupKey_differentSourcesNeverCollide() {
        RunWarning gmail = new RunWarning("gmail", "auth_failed", "");
        RunWarning instagram = new RunWarning("instagram:beachrepublicans", "auth_failed", "");
        assertNotEquals(gmail.dedupKey(), instagram.dedupKey());
    }
}
