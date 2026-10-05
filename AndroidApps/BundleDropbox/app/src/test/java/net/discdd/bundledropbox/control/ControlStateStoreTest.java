package net.discdd.bundledropbox.control;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
}
