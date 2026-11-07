package net.discdd.server.bugreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugReportHandlerTest {
    @Test
    void descriptionDropsAttachedLogs() {
        String report = "=== Bug Report - 2026-11-07 10:00:00 ===\napp froze on send\n\n=== System Logs ===\n[X] a\n[Y] b\n";
        assertEquals("=== Bug Report - 2026-11-07 10:00:00 ===\napp froze on send", BugReportHandler.description(report));
    }

    @Test
    void descriptionWithoutLogsSectionIsWholeReport() {
        assertEquals("plain text", BugReportHandler.description("  plain text \n"));
    }

    @Test
    void longDescriptionIsCapped() {
        String text = BugReportHandler.description("x".repeat(BugReportHandler.MAX_LOGGED_CHARS + 50));
        assertTrue(text.endsWith(" ... [see file]"));
        assertEquals(BugReportHandler.MAX_LOGGED_CHARS + " ... [see file]".length(), text.length());
    }
}
