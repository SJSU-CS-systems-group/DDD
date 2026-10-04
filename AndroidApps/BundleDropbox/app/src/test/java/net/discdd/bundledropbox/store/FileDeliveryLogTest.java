package net.discdd.bundledropbox.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** JVM tests for {@link FileDeliveryLog} and {@link KeepAllRetentionPolicy}. */
public class FileDeliveryLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void appendsAndReadsBackInOrder() throws IOException {
        DropboxPaths paths = new DropboxPaths(tmp.getRoot().toPath());   // meta dir created lazily
        FileDeliveryLog log = new FileDeliveryLog(paths);
        assertTrue(log.readAll().isEmpty());
        log.recordServed("d1", 10);
        log.recordServed("d2", 20);
        log.recordServed("d1", 30);
        List<FileDeliveryLog.Served> all = new FileDeliveryLog(paths).readAll();
        assertEquals(List.of(new FileDeliveryLog.Served("d1", 10), new FileDeliveryLog.Served("d2", 20),
                             new FileDeliveryLog.Served("d1", 30)), all);
        assertEquals(3, Files.readAllLines(paths.meta.resolve(FileDeliveryLog.FILE_NAME)).size());
    }

    @Test
    public void skipsTornAndMalformedLines() throws IOException {
        Path f = tmp.getRoot().toPath().resolve("log.jsonl");
        FileDeliveryLog log = new FileDeliveryLog(f);
        log.recordServed("ok1", 1);
        Files.write(f, "{\"bundleId\":\"torn\",\"serv\n\n[1,2]\n{\"servedAt\":5}\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        log.recordServed("ok2", 2);
        assertEquals(List.of(new FileDeliveryLog.Served("ok1", 1), new FileDeliveryLog.Served("ok2", 2)),
                     log.readAll());
    }

    /** Audit i4: an append after a torn last line (no trailing newline) must not merge into it. */
    @Test
    public void appendAfterTornLastLineStartsANewLine() throws IOException {
        Path f = tmp.getRoot().toPath().resolve("torn.jsonl");
        FileDeliveryLog log = new FileDeliveryLog(f);
        log.recordServed("before", 1);
        Files.write(f, "{\"bundleId\":\"cut-by-power-loss\",\"serv".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        log.recordServed("after", 2);
        assertEquals(List.of(new FileDeliveryLog.Served("before", 1), new FileDeliveryLog.Served("after", 2)),
                     log.readAll());
        assertEquals(3, Files.readAllLines(f).size());
    }

    @Test
    public void nullIdIgnored() {
        FileDeliveryLog log = new FileDeliveryLog(tmp.getRoot().toPath().resolve("x.jsonl"));
        log.recordServed(null, 1);
        assertTrue(log.readAll().isEmpty());
    }

    @Test
    public void keepAllNeverDeletes() {
        assertEquals(false, KeepAllRetentionPolicy.INSTANCE.mayDelete(
                new BundleRecord("id", Direction.DOWNSTREAM, 1, 1)));
    }
}
