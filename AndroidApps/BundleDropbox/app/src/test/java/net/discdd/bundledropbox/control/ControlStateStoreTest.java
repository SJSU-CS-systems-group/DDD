package net.discdd.bundledropbox.control;

import net.discdd.dropbox.control.InventoryReport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControlStateStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void failedSaveLeavesPreviousStateAvailableForReload() throws Exception {
        Path stateDir = tmp.getRoot().toPath().resolve("control");
        Path stateFile = stateDir.resolve(ControlStateStore.FILE_NAME);
        ControlStateStore store = new ControlStateStore(stateFile);
        store.recordReportQueued(1, "first", 100, List.of());

        Path displacedDir = stateDir.resolveSibling("control.saved");
        Files.move(stateDir, displacedDir);
        Files.write(stateDir, new byte[]{1}); // a regular file blocks creating the state directory/temp file
        try {
            assertThrows(IOException.class, () -> store.recordReportQueued(2, "second", 200, List.of()));
            assertEquals("failed persistence must not advance in-memory state", 1, store.snapshot().lastReportSeq());
        } finally {
            Files.delete(stateDir);
            Files.move(displacedDir, stateDir);
        }

        assertEquals("the last saved state remains reloadable", 1,
                     new ControlStateStore(stateFile).snapshot().lastReportSeq());
    }

    @Test
    public void delayedExactPlanAdvancesPersistedPageButUnrelatedPlanDoesNot() throws Exception {
        Path file = tmp.getRoot().toPath().resolve(ControlStateStore.FILE_NAME);
        ControlStateStore store = new ControlStateStore(file);
        store.recordInventoryQueued(page(1, "u100", "d100"), 1);
        store = new ControlStateStore(file);
        assertTrue(store.snapshot().hasPendingPage());

        // A plan for some other report can lift the sequence counter, but cannot acknowledge this page.
        store.recordPlanApplied(1, 99, result(1), 0, 0, 0, 0, 2);
        assertTrue(store.snapshot().hasPendingPage());
        assertEquals(null, store.snapshot().upstreamCursor());
        store.recordInventoryQueued(page(100, "u100", "d100"), 3);
        store.recordInventoryQueued(page(101, "u100", "d100"), 4); // same page, refreshed twice

        store = new ControlStateStore(file);
        store.recordPlanApplied(2, 1, result(2), 0, 0, 0, 0, 5); // delayed first report
        assertFalse(store.snapshot().hasPendingPage());
        assertEquals("u100", store.snapshot().upstreamCursor());
        assertEquals("d100", store.snapshot().downstreamCursor());
    }

    private static InventoryReport.PlanResult result(long planSeq) {
        return new InventoryReport.PlanResult(planSeq, List.of(), List.of());
    }

    private static InventoryBuilder.Draft page(long reportSeq, String upEnd, String downEnd) {
        InventoryReport report = new InventoryReport(reportSeq, 0, 0, 0, null, List.of(), List.of(), List.of());
        return new InventoryBuilder.Draft(report, new byte[0], "page", upEnd, downEnd, false);
    }
}
