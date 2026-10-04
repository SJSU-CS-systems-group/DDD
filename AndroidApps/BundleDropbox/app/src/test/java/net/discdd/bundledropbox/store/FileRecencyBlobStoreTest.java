package net.discdd.bundledropbox.store;

import com.google.protobuf.ByteString;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link FileRecencyBlobStore} with {@link ServerTrust}: only blobs a stock client of the same
 * server would accept are kept (SUCCESS, pinned key, valid Curve signature, at most 60 s ahead; Audit M5/M6),
 * only strictly newer ones replace the stored one, bytes are kept verbatim, and the blob survives a restart.
 */
public class FileRecencyBlobStoreTest {
    private static final long NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxPaths paths;
    private final AtomicLong clock = new AtomicLong(NOW);
    private ECKeyPair server;
    private ECKeyPair otherServer;
    private ServerTrust trust;

    @Before
    public void setUp() throws IOException {
        paths = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
        server = Curve.generateKeyPair();
        otherServer = Curve.generateKeyPair();
        trust = new ServerTrust(server.getPublicKey(), clock::get);
    }

    private FileRecencyBlobStore open() {
        return new FileRecencyBlobStore(paths, trust);
    }

    /** A response exactly as {@code ServerBundleTransmission.getRecencyBlob} builds it, signed by {@code signer}. */
    private static GetRecencyBlobResponse signed(ECKeyPair signer, RecencyBlobStatus status, long ts) {
        RecencyBlob blob = RecencyBlob.newBuilder()
                .setVersion(0)
                .setNonce(42)
                .setBlobTimestamp(ts)
                .setSenderId("carrier-sha1-id")
                .build();
        byte[] sig;
        try {
            sig = Curve.calculateSignature(signer.getPrivateKey(), blob.toByteArray());
        } catch (InvalidKeyException e) {
            throw new AssertionError(e);
        }
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(status)
                .setRecencyBlob(blob)
                .setRecencyBlobSignature(ByteString.copyFrom(sig))
                .setServerPublicKey(ByteString.copyFrom(signer.getPublicKey().serialize()))
                .build();
    }

    private byte[] success(long ts) {
        return signed(server, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, ts).toByteArray();
    }

    @Test
    public void emptyAtBootstrap() {
        FileRecencyBlobStore s = open();
        assertFalse(s.current().isPresent());
        assertFalse(s.currentResponse().isPresent());
        assertEquals(0, s.currentTimestamp());
    }

    @Test
    public void acceptsTrustedBlobAndKeepsExactBytes() throws IOException {
        FileRecencyBlobStore s = open();
        byte[] raw = success(NOW - 1_000);
        assertTrue(s.isTrusted(raw));
        assertTrue(s.offer(raw));
        assertArrayEquals(raw, s.current().get());
        assertEquals(NOW - 1_000, s.currentTimestamp());
        assertArrayEquals(raw, Files.readAllBytes(paths.recencyBlobFile));
        assertEquals("carrier-sha1-id", s.currentResponse().get().getRecencyBlob().getSenderId());
    }

    /** Audit M5: a carrier synced to another deployment must not replace our blob. */
    @Test
    public void blobFromAnotherServerIsRejected() {
        FileRecencyBlobStore s = open();
        assertTrue(s.offer(success(NOW - 5_000)));
        byte[] foreign = signed(otherServer, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, NOW).toByteArray();
        assertFalse(s.isTrusted(foreign));
        assertFalse("newer but foreign", s.offer(foreign));
        assertEquals(NOW - 5_000, s.currentTimestamp());
        assertEquals("signed by unknown server",
                     trust.rejectionReason(signed(otherServer, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, NOW)));
    }

    /** Audit M6: our key in the message is not enough; the signature must verify. */
    @Test
    public void forgedSignatureIsRejected() {
        GetRecencyBlobResponse genuine = signed(server, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, NOW);
        byte[] junkSig = genuine.toBuilder().setRecencyBlobSignature(ByteString.copyFrom(new byte[64]))
                .build().toByteArray();
        // Re-pointing a genuine signature at a different (tampered) timestamp must fail too.
        byte[] tampered = genuine.toBuilder()
                .setRecencyBlob(genuine.getRecencyBlob().toBuilder().setBlobTimestamp(NOW + 10))
                .build().toByteArray();
        byte[] noKey = genuine.toBuilder().clearServerPublicKey().build().toByteArray();
        FileRecencyBlobStore s = open();
        assertFalse(s.offer(junkSig));
        assertFalse(s.offer(tampered));
        assertFalse(s.offer(noKey));
        assertFalse(s.current().isPresent());
    }

    /** Audit M6: a far-future timestamp would win forever and every client rejects it. */
    @Test
    public void futureTimestampBeyondSixtySecondsIsRejected() {
        FileRecencyBlobStore s = open();
        assertFalse(s.offer(success(Long.MAX_VALUE)));
        assertFalse(s.offer(success(NOW + ServerTrust.MAX_FUTURE_SKEW_MS + 1)));
        assertTrue("within the client's 1 minute skew", s.offer(success(NOW + ServerTrust.MAX_FUTURE_SKEW_MS)));
    }

    @Test
    public void onlyStrictlyNewerReplaces() {
        FileRecencyBlobStore s = open();
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
        FileRecencyBlobStore s = open();
        assertTrue(s.offer(success(5)));
        // A transport that never synced serves the default instance (status UNDEFINED, ts 0).
        assertFalse(s.offer(GetRecencyBlobResponse.getDefaultInstance().toByteArray()));
        assertFalse(s.offer(signed(server, RecencyBlobStatus.RECENCY_BLOB_STATUS_UNDEFINED, 99).toByteArray()));
        assertFalse(s.offer(signed(server, RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED, 99).toByteArray()));
        assertEquals(5, s.currentTimestamp());
    }

    @Test
    public void successWithoutBlobRejected() {
        FileRecencyBlobStore s = open();
        byte[] raw = GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .build()
                .toByteArray();
        assertFalse(s.offer(raw));
    }

    @Test
    public void garbageAndEmptyRejected() {
        FileRecencyBlobStore s = open();
        assertFalse(s.offer(null));
        assertFalse(s.offer(new byte[0]));
        assertFalse(s.offer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        assertFalse(s.isTrusted(null));
        assertFalse(s.current().isPresent());
    }

    @Test
    public void survivesRestart() {
        byte[] raw = success(7_777);
        assertTrue(open().offer(raw));
        FileRecencyBlobStore reopened = open();
        assertArrayEquals(raw, reopened.current().get());
        assertEquals(7_777, reopened.currentTimestamp());
        assertFalse(reopened.offer(success(7_000)));
    }

    /** Audit M6 recovery: a poisoned blob stored by an older, non-verifying build is dropped on load. */
    @Test
    public void untrustedBlobOnDiskIsDroppedAtLoad() throws IOException {
        Files.write(paths.recencyBlobFile,
                    signed(otherServer, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, Long.MAX_VALUE).toByteArray());
        FileRecencyBlobStore s = open();
        assertFalse(s.current().isPresent());
        assertTrue("a genuine blob is accepted again", s.offer(success(1)));
    }

    @Test
    public void unusableFileOnDiskTreatedAsNone() throws IOException {
        Files.write(paths.recencyBlobFile, signed(server, RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED, 50).toByteArray());
        FileRecencyBlobStore s = open();
        assertFalse(s.current().isPresent());
        assertTrue(s.offer(success(1)));
    }

    @Test
    public void changingTheServerKeyDropsTheOldServersBlob() {
        FileRecencyBlobStore s = open();
        assertTrue(s.offer(success(100)));
        assertTrue("same key keeps the blob", s.setVerifier(new ServerTrust(server.getPublicKey(), clock::get)));
        assertEquals(100, s.currentTimestamp());
        assertFalse(s.setVerifier(new ServerTrust(otherServer.getPublicKey(), clock::get)));
        assertFalse(s.current().isPresent());
        assertFalse(Files.exists(paths.recencyBlobFile));
        assertTrue(s.offer(signed(otherServer, RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, 50).toByteArray()));
    }

    @Test
    public void resetReturnsToBootstrap() {
        FileRecencyBlobStore s = open();
        assertTrue(s.offer(success(100)));
        s.reset();
        assertEquals(0, s.currentTimestamp());
        assertFalse(s.current().isPresent());
        assertFalse(open().current().isPresent());
        assertTrue("an older but genuine blob is accepted after a reset", s.offer(success(50)));
    }

    @Test
    public void currentReturnsDefensiveCopy() {
        FileRecencyBlobStore s = open();
        byte[] raw = success(10);
        byte[] expected = raw.clone();
        s.offer(raw);
        raw[0] ^= 0x7F;                     // caller mutates its array after offering
        byte[] got = s.current().get();
        got[1] ^= 0x7F;                     // caller mutates the returned array
        assertEquals(10, s.currentTimestamp());
        assertArrayEquals(expected, s.current().get());
    }

    @Test
    public void noTempFilesLeftBehind() throws IOException {
        FileRecencyBlobStore s = open();
        s.offer(success(1));
        s.offer(success(2));
        try (var files = Files.list(paths.meta)) {
            assertEquals(1, files.map(Path::getFileName).filter(p -> p.toString().contains(".tmp-") ||
                    p.toString().equals(DropboxPaths.RECENCY_BLOB_FILE_NAME)).count());
        }
    }
}
