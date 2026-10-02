package net.discdd.bundledropbox.store;

import com.google.protobuf.ByteString;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link FileRecencyBlobStore}: only SUCCESS blobs with strictly newer timestamps are
 * kept, bytes are kept verbatim, and the blob survives a restart.
 */
public class FileRecencyBlobStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxPaths paths;

    @Before
    public void setUp() throws IOException {
        paths = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
    }

    private static byte[] blob(RecencyBlobStatus status, long ts) {
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(status)
                .setRecencyBlob(RecencyBlob.newBuilder()
                                        .setVersion(0)
                                        .setNonce(42)
                                        .setBlobTimestamp(ts)
                                        .setSenderId("carrier-sha1-id"))
                .setRecencyBlobSignature(ByteString.copyFrom(new byte[]{1, 2, 3, 4}))
                .setServerPublicKey(ByteString.copyFrom(new byte[]{5, 6, 7}))
                .build()
                .toByteArray();
    }

    private static byte[] success(long ts) {
        return blob(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, ts);
    }

    @Test
    public void emptyAtBootstrap() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        assertFalse(s.current().isPresent());
        assertFalse(s.currentResponse().isPresent());
        assertEquals(0, s.currentTimestamp());
    }

    @Test
    public void acceptsSuccessAndKeepsExactBytes() throws IOException {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        byte[] raw = success(1_000);
        assertTrue(s.offer(raw));
        assertArrayEquals(raw, s.current().get());
        assertEquals(1_000, s.currentTimestamp());
        assertArrayEquals(raw, Files.readAllBytes(paths.recencyBlobFile));
        assertEquals("carrier-sha1-id", s.currentResponse().get().getRecencyBlob().getSenderId());
    }

    @Test
    public void onlyStrictlyNewerReplaces() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        assertTrue(s.offer(success(2_000)));
        assertFalse("older must not replace", s.offer(success(1_999)));
        assertFalse("equal must not replace", s.offer(success(2_000)));
        assertEquals(2_000, s.currentTimestamp());
        byte[] newer = success(2_001);
        assertTrue(s.offer(newer));
        assertArrayEquals(newer, s.current().get());
    }

    @Test
    public void nonSuccessStatusesRejected() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        assertTrue(s.offer(success(5)));
        // A transport that never synced serves the default instance (status UNDEFINED, ts 0).
        assertFalse(s.offer(GetRecencyBlobResponse.getDefaultInstance().toByteArray()));
        assertFalse(s.offer(blob(RecencyBlobStatus.RECENCY_BLOB_STATUS_UNDEFINED, 99)));
        assertFalse(s.offer(blob(RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED, 99)));
        assertEquals(5, s.currentTimestamp());
    }

    @Test
    public void successWithoutBlobRejected() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        byte[] raw = GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .build()
                .toByteArray();
        assertFalse(s.offer(raw));
    }

    @Test
    public void garbageAndEmptyRejected() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        assertFalse(s.offer(null));
        assertFalse(s.offer(new byte[0]));
        assertFalse(s.offer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        assertFalse(s.current().isPresent());
    }

    @Test
    public void survivesRestart() {
        byte[] raw = success(7_777);
        assertTrue(new FileRecencyBlobStore(paths).offer(raw));
        FileRecencyBlobStore reopened = new FileRecencyBlobStore(paths);
        assertArrayEquals(raw, reopened.current().get());
        assertEquals(7_777, reopened.currentTimestamp());
        assertFalse(reopened.offer(success(7_000)));
    }

    @Test
    public void unusableFileOnDiskTreatedAsNone() throws IOException {
        Files.write(paths.recencyBlobFile, blob(RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED, 50));
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        assertFalse(s.current().isPresent());
        assertTrue(s.offer(success(1)));
    }

    @Test
    public void currentReturnsDefensiveCopy() {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        byte[] raw = success(10);
        s.offer(raw);
        raw[0] ^= 0x7F;                     // caller mutates its array after offering
        byte[] got = s.current().get();
        got[1] ^= 0x7F;                     // caller mutates the returned array
        assertEquals(10, s.currentTimestamp());
        assertArrayEquals(success(10), s.current().get());
    }

    @Test
    public void noTempFilesLeftBehind() throws IOException {
        FileRecencyBlobStore s = new FileRecencyBlobStore(paths);
        s.offer(success(1));
        s.offer(success(2));
        try (var files = Files.list(paths.meta)) {
            assertEquals(1, files.map(Path::getFileName).filter(p -> p.toString().contains(".tmp-") ||
                    p.toString().equals(DropboxPaths.RECENCY_BLOB_FILE_NAME)).count());
        }
    }
}
