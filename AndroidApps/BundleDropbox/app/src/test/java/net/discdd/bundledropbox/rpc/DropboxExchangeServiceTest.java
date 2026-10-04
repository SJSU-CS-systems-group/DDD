package net.discdd.bundledropbox.rpc;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundlesecurity.BundleOwnershipPSI;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIElement;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Behaviour of {@link DropboxExchangeService} called directly (no network): upload staging/commit/abort,
 * PSI over DOWNSTREAM, legacy download, recency blob serving.
 */
public class DropboxExchangeServiceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RpcTestFakes.FakeRelayStore store;
    private RpcTestFakes.FakeRecencyBlobStore blobs;
    private RpcTestFakes.FakeDeliveryLog deliveryLog;
    private RpcTestFakes.RecordingListener listener;
    private RpcTestFakes.FakeClock clock;
    private DropboxExchangeService service;

    @Before
    public void setUp() throws Exception {
        store = new RpcTestFakes.FakeRelayStore(tmp.newFolder("store").toPath());
        blobs = new RpcTestFakes.FakeRecencyBlobStore();
        deliveryLog = new RpcTestFakes.FakeDeliveryLog();
        listener = new RpcTestFakes.RecordingListener();
        clock = new RpcTestFakes.FakeClock();
        service = new DropboxExchangeService(store, blobs, deliveryLog, listener, clock, () -> 42L);
    }

    // ---- upload ----

    private BundleUploadResponse upload(String id, boolean sendSenderType, boolean complete, byte[]... chunks) {
        var resp = new RpcTestFakes.RecordingObserver<BundleUploadResponse>();
        StreamObserver<BundleUploadRequest> req = service.uploadBundle(resp);
        req.onNext(BundleUploadRequest.newBuilder()
                           .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(id).build())
                           .build());
        for (byte[] c : chunks) {
            req.onNext(BundleUploadRequest.newBuilder()
                               .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(c)).build())
                               .build());
        }
        if (sendSenderType) {
            req.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
        }
        if (complete) {
            req.onCompleted();
        } else {
            req.onError(Status.CANCELLED.asException());
        }
        return resp.values.isEmpty() ? null : resp.values.get(0);
    }

    @Test
    public void successfulUploadIsCommittedUnderClaimedId() throws Exception {
        BundleUploadResponse r = upload("bundle-1", true, true, "hello ".getBytes(), "world".getBytes());
        assertEquals(net.discdd.grpc.Status.SUCCESS, r.getStatus());
        assertTrue(store.contains(Direction.UPSTREAM, "bundle-1"));
        assertEquals("hello world",
                     new String(Files.readAllBytes(store.pathFor(Direction.UPSTREAM, "bundle-1")),
                                StandardCharsets.UTF_8));
        assertEquals(0, store.stagedFileCount());
        assertEquals(1, listener.committed.size());
    }

    @Test
    public void cancelledUploadIsAbortedNotCommitted() throws Exception {
        upload("bundle-2", false, false, "partial".getBytes());
        assertNull(store.pathFor(Direction.UPSTREAM, "bundle-2"));
        assertEquals("no partial file left in staging", 0, store.stagedFileCount());
        assertEquals(1, listener.failed.size());
    }

    @Test
    public void uploadWithoutSenderTypeIsNotCommitted() throws Exception {
        BundleUploadResponse r = upload("bundle-3", false, true, "x".getBytes());
        assertEquals(net.discdd.grpc.Status.FAILED, r.getStatus());
        assertNull(store.pathFor(Direction.UPSTREAM, "bundle-3"));
        assertEquals(0, store.stagedFileCount());
    }

    @Test
    public void uncleanIdIsRefused() throws Exception {
        BundleUploadResponse r = upload("../../etc/passwd", true, true, "x".getBytes());
        assertEquals(net.discdd.grpc.Status.FAILED, r.getStatus());
        assertEquals(0, store.stagedFileCount());
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
    }

    @Test
    public void fullStoreRefusesUpload() throws Exception {
        store.capacityBytes = 0;
        store.putDownstream("d1", new byte[] { 1 }); // used 1 > capacity 0
        BundleUploadResponse r = upload("bundle-4", true, true, "x".getBytes());
        assertEquals(net.discdd.grpc.Status.FAILED, r.getStatus());
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
        assertEquals(0, store.stagedFileCount());
        assertTrue(listener.failed.get(0).contains("store full"));
    }

    /** Audit m9: a second bundleId in one stream fails the upload instead of leaking bundle-core's writer. */
    @Test
    public void repeatedBundleIdInOneStreamIsRefusedAndNothingIsLeftStaged() throws Exception {
        var resp = new RpcTestFakes.RecordingObserver<BundleUploadResponse>();
        StreamObserver<BundleUploadRequest> req = service.uploadBundle(resp);
        req.onNext(idMsg("first"));
        req.onNext(chunkMsg("part one".getBytes()));
        req.onNext(idMsg("second"));
        req.onNext(chunkMsg("part two".getBytes()));   // dropped: the call already answered
        req.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
        req.onCompleted();
        assertEquals(1, resp.values.size());
        assertEquals(net.discdd.grpc.Status.FAILED, resp.values.get(0).getStatus());
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
        assertEquals(0, store.stagedFileCount());
        assertEquals(1, store.aborted.size());
        assertTrue(listener.failed.toString(), listener.failed.get(0).contains("repeated bundleId"));
    }

    /** Audit m7: the capacity is enforced per chunk, before the bytes reach the disk. */
    @Test
    public void uploadExceedingCapacityMidStreamIsCutOff() throws Exception {
        store.capacityBytes = 1_000;
        var resp = new RpcTestFakes.RecordingObserver<BundleUploadResponse>();
        StreamObserver<BundleUploadRequest> req = service.uploadBundle(resp);
        req.onNext(idMsg("too-big"));
        req.onNext(chunkMsg(new byte[600]));
        req.onNext(chunkMsg(new byte[600]));   // would reach 1,200 bytes
        req.onNext(chunkMsg(new byte[600]));   // dropped
        req.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
        req.onCompleted();
        assertEquals(1, resp.values.size());
        assertEquals(net.discdd.grpc.Status.FAILED, resp.values.get(0).getStatus());
        assertTrue(resp.completed);
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
        assertEquals("staged partial removed", 0, store.stagedFileCount());
        assertTrue(listener.failed.toString(), listener.failed.get(0).contains("store full"));
    }

    /** Audit i3: user-visible (INFO+) log lines must not pair a client IP with its bundle id. */
    @Test
    public void infoLogsDoNotNameTheUploadedBundle() throws Exception {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(DropboxExchangeService.class.getName());
        List<String> infoLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel().intValue() >= java.util.logging.Level.INFO.intValue()) infoLines.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.addHandler(h);
        try {
            upload("secret-bundle-id", true, true, "x".getBytes());
            upload("other-secret-id", false, true, "x".getBytes());
        } finally {
            log.removeHandler(h);
        }
        assertTrue(infoLines.size() >= 2);
        for (String line : infoLines) {
            assertTrue(line, !line.contains("secret-bundle-id") && !line.contains("other-secret-id"));
        }
    }

    private static BundleUploadRequest idMsg(String id) {
        return BundleUploadRequest.newBuilder()
                .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(id).build())
                .build();
    }

    private static BundleUploadRequest chunkMsg(byte[] b) {
        return BundleUploadRequest.newBuilder()
                .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(b)).build())
                .build();
    }

    @Test
    public void reUploadOfSameIdReplaces() throws Exception {
        upload("same", true, true, "one".getBytes());
        upload("same", true, true, "two".getBytes());
        assertEquals(1, store.list(Direction.UPSTREAM).size());
        assertEquals("two", new String(Files.readAllBytes(store.pathFor(Direction.UPSTREAM, "same")),
                                       StandardCharsets.UTF_8));
    }

    // ---- recency blob ----

    @Test
    public void recencyBlobServedVerbatimOrDefault() throws Exception {
        var resp = new RpcTestFakes.RecordingObserver<GetRecencyBlobResponse>();
        service.getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance(), resp);
        assertEquals(GetRecencyBlobResponse.getDefaultInstance(), resp.values.get(0));

        GetRecencyBlobResponse blob = GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(RecencyBlob.newBuilder().setBlobTimestamp(123).setNonce(7).setSenderId("carrier"))
                .setRecencyBlobSignature(ByteString.copyFromUtf8("sig"))
                .setServerPublicKey(ByteString.copyFromUtf8("pub"))
                .build();
        blobs.blob = blob.toByteArray();
        var resp2 = new RpcTestFakes.RecordingObserver<GetRecencyBlobResponse>();
        service.getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance(), resp2);
        assertTrue(resp2.completed);
        assertArrayEquals(blob.toByteArray(), resp2.values.get(0).toByteArray());
    }

    // ---- PSI ----

    private PSIResponse psiExchange(BundleOwnershipPSI psi, BigInteger secret, List<String> wanted) {
        var req = PSIRequest.newBuilder();
        for (BigInteger v : psi.clientBlindBundleIds(wanted, secret)) {
            req.addClientBlindedValues(PSIElement.newBuilder().setValue(ByteString.copyFrom(v.toByteArray())));
        }
        var resp = new RpcTestFakes.RecordingObserver<PSIResponse>();
        service.psiExchange(req.build(), resp);
        assertNull(resp.error);
        return resp.values.get(0);
    }

    private static List<BigInteger> ints(List<PSIElement> els) {
        List<BigInteger> out = new ArrayList<>();
        for (PSIElement e : els) out.add(new BigInteger(1, e.getValue().toByteArray()));
        return out;
    }

    @Test
    public void psiFindsAndDownloadsDownstreamBundleThenSessionIsSingleUse() throws Exception {
        store.putDownstream("other-bundle", "nope".getBytes());
        store.putDownstream("my-bundle", "payload".getBytes());
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        PSIResponse r = psiExchange(psi, a, List.of("not-here", "my-bundle"));
        var matches = psi.clientFindMatches(ints(r.getDoublyBlindedClientValuesList()),
                                            ints(r.getTransportBlindedValuesList()), a);
        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).clientIndex());

        var dl = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        PSIDownloadRequest req = PSIDownloadRequest.newBuilder()
                .setSessionId(r.getSessionId())
                .setTransportIndex(matches.get(0).transportIndex())
                .build();
        service.psiDownloadBundle(req, dl);
        assertTrue(dl.completed);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (BundleDownloadResponse c : dl.values) bytes.write(c.getChunk().getChunk().toByteArray());
        assertEquals("payload", bytes.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("my-bundle"), deliveryLog.served);
        assertEquals(List.of("my-bundle"), listener.served);

        var again = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.psiDownloadBundle(req, again);
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(again.error).getCode());
    }

    @Test
    public void psiOnlySeesCommittedDownstream() throws Exception {
        // An upstream bundle and a staged file must never be offered to clients.
        upload("up-only", true, true, "x".getBytes());
        store.beginStaging(Direction.DOWNSTREAM);
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        PSIResponse r = psiExchange(psi, a, List.of("up-only"));
        assertEquals(0, r.getTransportBlindedValuesCount());
    }

    @Test
    public void psiRejectsMoreThanTenElements() {
        var req = PSIRequest.newBuilder();
        for (int i = 0; i < 11; i++) {
            req.addClientBlindedValues(PSIElement.newBuilder().setValue(ByteString.copyFrom(new byte[] { 1 })));
        }
        var resp = new RpcTestFakes.RecordingObserver<PSIResponse>();
        service.psiExchange(req.build(), resp);
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(resp.error).getCode());
    }

    @Test
    public void psiSessionExpiresAfterTtl() throws Exception {
        store.putDownstream("b", "x".getBytes());
        var psi = new BundleOwnershipPSI();
        PSIResponse r = psiExchange(psi, psi.generateSecret(), List.of("b"));
        clock.advance(DropboxExchangeService.PSI_SESSION_TTL_MS + 1);
        var dl = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.psiDownloadBundle(PSIDownloadRequest.newBuilder().setSessionId(r.getSessionId())
                                          .setTransportIndex(0).build(), dl);
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(dl.error).getCode());
        assertEquals(0, service.psiSessionCount());
    }

    @Test
    public void psiDownloadOfClearedBundleIsNotFound() throws Exception {
        store.putDownstream("gone", "x".getBytes());
        var psi = new BundleOwnershipPSI();
        PSIResponse r = psiExchange(psi, psi.generateSecret(), List.of("gone"));
        store.delete(Direction.DOWNSTREAM, "gone");
        var dl = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.psiDownloadBundle(PSIDownloadRequest.newBuilder().setSessionId(r.getSessionId())
                                          .setTransportIndex(0).build(), dl);
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(dl.error).getCode());
    }

    @Test
    public void psiSnapshotCapKeepsTheMostRecentlySeenBundles() throws Exception {
        service = new DropboxExchangeService(store, blobs, deliveryLog, listener, clock, () -> 42L, 2);
        store.putDownstream("oldest", "1".getBytes(), null, -1, 100, 100);
        store.putDownstream("middle", "2".getBytes(), null, -1, 200, 200);
        store.putDownstream("newest", "3".getBytes(), null, -1, 300, 300);
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        PSIResponse r = psiExchange(psi, a, List.of("oldest", "newest"));
        assertEquals("snapshot capped", 2, r.getTransportBlindedValuesCount());
        var matches = psi.clientFindMatches(ints(r.getDoublyBlindedClientValuesList()),
                                            ints(r.getTransportBlindedValuesList()), a);
        assertEquals("only the most recently seen bundles are offered", 1, matches.size());
        assertEquals(1, matches.get(0).clientIndex());
    }

    /**
     * Audit M9: a client's current bundle that a carrier keeps re-offering ({@code touch}) stays in the capped
     * snapshot even after newer bundles for other clients arrived.
     */
    @Test
    public void reOfferedBundleDoesNotAgeOutOfTheCappedSnapshot() throws Exception {
        service = new DropboxExchangeService(store, blobs, deliveryLog, listener, clock, () -> 42L, 1);
        store.putDownstream("idle-client-current", "1".getBytes(), "IDLE", 1_000, 100, 100);
        store.putDownstream("busy-client", "2".getBytes(), "BUSY", 2_000, 200, 200);
        assertTrue(store.touch(Direction.DOWNSTREAM, "idle-client-current")); // carrier re-offered it just now
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        PSIResponse r = psiExchange(psi, a, List.of("idle-client-current"));
        assertEquals(1, r.getTransportBlindedValuesCount());
        assertEquals(1, psi.clientFindMatches(ints(r.getDoublyBlindedClientValuesList()),
                                              ints(r.getTransportBlindedValuesList()), a).size());
    }

    /**
     * Audit M7: the stock client downloads only its first match in window (ascending counter) order, so a
     * superseded version must not be offered or it would take the stale one first.
     */
    @Test
    public void psiOffersOnlyTheNewestBundlePerClient() throws Exception {
        store.putDownstream("x-counter5", "old".getBytes(), "CLIENT-X", 5_000, 900, 900); // arrived last
        store.putDownstream("x-counter6", "new".getBytes(), "CLIENT-X", 6_000, 100, 100);
        store.putDownstream("y-counter1", "y".getBytes(), "CLIENT-Y", 1_000, 50, 50);
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        // The client's window lists counters in ascending order: 5 before 6.
        PSIResponse r = psiExchange(psi, a, List.of("x-counter5", "x-counter6"));
        assertEquals("one bundle per client", 2, r.getTransportBlindedValuesCount());
        var matches = psi.clientFindMatches(ints(r.getDoublyBlindedClientValuesList()),
                                            ints(r.getTransportBlindedValuesList()), a);
        assertEquals(1, matches.size());
        assertEquals("the newer counter is the only match", 1, matches.get(0).clientIndex());
        assertTrue("older version is kept, just not offered", store.contains(Direction.DOWNSTREAM, "x-counter5"));
    }

    @Test
    public void legacyDownloadServesOnlyTheNewestBundlePerClient() throws Exception {
        store.putDownstream("x-counter5", "old".getBytes(), "CLIENT-X", 5_000, 100, 100);
        store.putDownstream("x-counter6", "new".getBytes(), "CLIENT-X", 6_000, 200, 200);
        var old = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("x-counter5"), old);
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(old.error).getCode());
        var current = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("x-counter6"), current);
        assertTrue(current.completed);
        assertEquals(List.of("x-counter6"), deliveryLog.served);
    }

    @Test
    public void largeDownloadsAreChunkedBelowClientInboundLimit() throws Exception {
        // A stock client keeps gRPC's default 4 MiB inbound limit; bundle-core's transferToStream would send a
        // file of 4 MiB or more as full 4 MiB messages, which that client rejects.
        byte[] big = new byte[DropboxExchangeService.DOWNLOAD_CHUNK_BYTES * 4 + 12_345];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        store.putDownstream("big", big);

        var dl = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("big"), dl);
        assertTrue(dl.completed);
        assertEquals(5, dl.values.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (BundleDownloadResponse c : dl.values) {
            assertTrue(c.getChunk().getChunk().size() <= DropboxExchangeService.DOWNLOAD_CHUNK_BYTES);
            assertTrue(c.getSerializedSize() < 4 * 1024 * 1024);
            bytes.write(c.getChunk().getChunk().toByteArray());
        }
        assertTrue(java.util.Arrays.equals(big, bytes.toByteArray()));

        var psi = new BundleOwnershipPSI();
        PSIResponse r = psiExchange(psi, psi.generateSecret(), List.of("big"));
        var psiDl = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.psiDownloadBundle(PSIDownloadRequest.newBuilder().setSessionId(r.getSessionId())
                                          .setTransportIndex(0).build(), psiDl);
        assertTrue(psiDl.completed);
        int total = 0;
        for (BundleDownloadResponse c : psiDl.values) {
            assertTrue(c.getChunk().getChunk().size() <= DropboxExchangeService.DOWNLOAD_CHUNK_BYTES);
            total += c.getChunk().getChunk().size();
        }
        assertEquals(big.length, total);
    }

    // ---- legacy download ----

    @Test
    public void legacyDownloadServesDownstreamOnly() throws Exception {
        store.putDownstream("down-1", "abc".getBytes());
        upload("up-1", true, true, "x".getBytes());

        var ok = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("down-1"), ok);
        assertTrue(ok.completed);
        assertEquals(List.of("down-1"), deliveryLog.served);

        var miss = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("up-1"), miss);
        assertNotNull(miss.error);
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(miss.error).getCode());

        var bad = new RpcTestFakes.RecordingObserver<BundleDownloadResponse>();
        service.downloadBundle(dlReq("../x"), bad);
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(bad.error).getCode());
    }

    private static BundleDownloadRequest dlReq(String id) {
        return BundleDownloadRequest.newBuilder()
                .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(id))
                .setSenderType(BundleSenderType.CLIENT)
                .build();
    }
}
