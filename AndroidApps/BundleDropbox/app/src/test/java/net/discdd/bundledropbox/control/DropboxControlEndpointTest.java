package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileRecencyBlobStore;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundledropbox.store.ServerTrust;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.bundledropbox.visit.ControlChannel;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryCompleteness;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RecencyRequest;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.ADU;
import net.discdd.model.Bundle;
import net.discdd.model.UncompressedBundle;
import net.discdd.utils.BundleUtils;
import com.google.protobuf.ByteString;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Real round trips between the dropbox's {@link DropboxControlEndpoint} (a real bundle-core
 * {@code ClientBundleTransmission}) and a minimal in-test server built from bundle-core's {@code ServerSecurity} and
 * {@code BundleUtils}, the same calls {@code ServerBundleTransmission} makes. Covers the Research/R7 rules: the
 * addressed-to-me test by bundle-id decryption, processing a copy, replay safety, the app-id backstop, the
 * blank-file repair, server-key changes; and {@link DropboxControlManager} end to end (inventory out, plan in,
 * relay-store deletions, results in the next inventory, start-up adoption, and the own recency blob: request out,
 * grant in, stored only when minted for the dropbox's own sender id; 09 Own Recency Blob Design).
 *
 * <p><b>One endpoint root per JVM.</b> bundle-core keeps {@code ClientSecurity} and {@code ClientRouting} as
 * process-wide singletons, so every test here shares one static root, wiped before each test (the endpoint then
 * starts a fresh identity). No other test class opens an endpoint; if one ever must, give it its own JVM
 * ({@code forkEvery}) instead. The in-test server is a singleton too: {@code SecurityUtils.getClientID} reads
 * {@code ServerSecurity.getInstance}.
 */
public class DropboxControlEndpointTest {
    @ClassRule
    public static TemporaryFolder classTmp = new TemporaryFolder();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path root;
    private static ServerKeyFiles keysA;
    private static ServerKeyFiles keysB;
    private static FakeServer server;

    private final List<RetentionPlan> plans = new ArrayList<>();
    private final List<RecencyGrant> grants = new ArrayList<>();
    private final AtomicBoolean sinkFails = new AtomicBoolean();

    /** The server's three public key files (what the dropbox imports) next to their private halves. */
    private static final class ServerKeyFiles {
        final Path serverRoot;
        final byte[] identityPem;
        final byte[] signedPrePem;
        final byte[] ratchetPem;

        ServerKeyFiles(Path serverRoot) throws IOException {
            this.serverRoot = serverRoot;
            Path dir = Files.createDirectories(serverRoot.resolve(SecurityUtils.SERVER_KEY_PATH));
            ECKeyPair id = Curve.generateKeyPair();
            IdentityKeyPair identity = new IdentityKeyPair(new IdentityKey(id.getPublicKey()), id.getPrivateKey());
            ECKeyPair pre = Curve.generateKeyPair();
            ECKeyPair ratchet = Curve.generateKeyPair();
            identityPem = pub(dir, SecurityUtils.SERVER_IDENTITY_KEY, identity.getPublicKey().serialize());
            pvt(dir, SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY, identity.serialize());
            signedPrePem = pub(dir, SecurityUtils.SERVER_SIGNED_PRE_KEY, pre.getPublicKey().serialize());
            pvt(dir, SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY, pre.getPrivateKey().serialize());
            ratchetPem = pub(dir, SecurityUtils.SERVER_RATCHET_KEY, ratchet.getPublicKey().serialize());
            pvt(dir, SecurityUtils.SERVER_RATCHET_PRIVATE_KEY, ratchet.getPrivateKey().serialize());
        }

        private static byte[] pub(Path dir, String name, byte[] key) throws IOException {
            byte[] pem = DDDPEMEncoder.encode(key, DDDPEMEncoder.ECPublicKeyType).getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve(name), pem);
            return pem;
        }

        private static void pvt(Path dir, String name, byte[] key) throws IOException {
            Files.write(dir.resolve(name), DDDPEMEncoder.encode(key, DDDPEMEncoder.ECPrivateKeyType).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** What a server does with a dropbox bundle, and how it builds one for the dropbox (ServerBundleTransmission). */
    private static final class FakeServer {
        final ServerSecurity security;
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        FakeServer(Path serverRoot) {
            security = ServerSecurity.getInstance(serverRoot);
        }

        /** Decrypts an upstream bundle and returns its payload jar entries of interest. */
        Received receive(Path jar, Path work) throws Exception {
            Files.createDirectories(work);
            UncompressedBundle ub = BundleUtils.extractBundle(new Bundle(jar.toFile()), work);
            Path payload = security.decrypt(ub.getSource().toPath(), ub.getSource().toPath());
            Received r = new Received();
            try (ZipFile zip = new ZipFile(payload.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName().replace('\\', '/');
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    if (name.startsWith("ADU/" + DropboxControl.APP_ID + "/")) r.controlAdus.add(bytes);
                    if (name.startsWith("ADU/" + DropboxControl.RECENCY_APP_ID + "/")) r.recencyAdus.add(bytes);
                    if (name.equals("acknowledgement.txt")) r.ack = new String(bytes, StandardCharsets.UTF_8).trim();
                }
            }
            return r;
        }

        /** Builds downstream bundle {@code counter} for {@code clientId} carrying one control ADU. */
        Path send(String clientId, long counter, long aduId, byte[] aduBytes, String ackedId, Path outDir)
                throws Exception {
            return send(DropboxControl.APP_ID, clientId, counter, aduId, aduBytes, ackedId, outDir);
        }

        /** Builds downstream bundle {@code counter} for {@code clientId} carrying one ADU under {@code appId}. */
        Path send(String appId, String clientId, long counter, long aduId, byte[] aduBytes, String ackedId,
                  Path outDir) throws Exception {
            Files.createDirectories(outDir);
            String encryptedId = security.createEncryptedBundleId(clientId, counter, BundleIDGenerator.DOWNSTREAM);
            Path aduFile = outDir.resolve("adu-" + counter);
            Files.write(aduFile, aduBytes);
            ADU adu = new ADU(aduFile.toFile(), appId, aduId, aduBytes.length);
            PipedInputStream pipe = new PipedInputStream();
            // app_ids.txt deliberately WITHOUT the control app ids: the endpoint's backstop must keep reports and
            // recency requests flowing.
            Future<?> f = BundleUtils.runFuture(executor, ackedId, null, List.of(adu), null, pipe,
                                                List.of("net.discdd.other"));
            Path jar = outDir.resolve(encryptedId);
            try (OutputStream out = Files.newOutputStream(jar)) {
                BundleUtils.encryptPayloadAndCreateBundle((in, o) -> security.encrypt(clientId, in, o),
                                                          security.getClientIdentityPublicKey(clientId),
                                                          security.getClientBaseKey(clientId),
                                                          security.getIdentityPublicKey().getPublicKey(),
                                                          encryptedId,
                                                          pipe,
                                                          out);
            }
            f.get();
            return jar;
        }

        /** Mints a blob for {@code senderId} exactly as {@code ServerBundleTransmission.getRecencyBlob} does. */
        GetRecencyBlobResponse mint(String senderId, long timestamp) throws Exception {
            RecencyBlob blob = RecencyBlob.newBuilder()
                    .setVersion(0)
                    .setNonce(42)
                    .setBlobTimestamp(timestamp)
                    .setSenderId(senderId)
                    .build();
            return GetRecencyBlobResponse.newBuilder()
                    .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                    .setRecencyBlob(blob)
                    .setRecencyBlobSignature(ByteString.copyFrom(SecurityUtils.signMessageRaw(blob.toByteArray(),
                                                                                             security.getSigningKey())))
                    .setServerPublicKey(ByteString.copyFrom(security.getIdentityPublicKey().serialize()))
                    .build();
        }
    }

    private static final class Received {
        final List<byte[]> controlAdus = new ArrayList<>();
        final List<byte[]> recencyAdus = new ArrayList<>();
        String ack;

        InventoryReport onlyInventory() throws Exception {
            assertEquals(1, controlAdus.size());
            return DropboxControlCodec.decodeInventory(controlAdus.get(0));
        }

        RecencyRequest onlyRecencyRequest() throws Exception {
            assertEquals(1, recencyAdus.size());
            return DropboxControlCodec.decodeRecencyRequest(recencyAdus.get(0));
        }
    }

    @BeforeClass
    public static void setUpClass() throws IOException {
        root = classTmp.newFolder("ControlEndpoint").toPath();
        keysA = new ServerKeyFiles(classTmp.newFolder("serverA").toPath());
        keysB = new ServerKeyFiles(classTmp.newFolder("serverB").toPath());
        server = new FakeServer(keysA.serverRoot);
    }

    @AfterClass
    public static void tearDownClass() {
        server.executor.shutdownNow();
    }

    @Before
    public void wipeEndpointRoot() throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            for (Path c : children.collect(Collectors.toList())) {
                try (Stream<Path> walk = Files.walk(c)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    private DropboxControlEndpoint open(ServerKeyFiles keys) throws Exception {
        return DropboxControlEndpoint.open(root, keys.identityPem, keys.signedPrePem, keys.ratchetPem, plan -> {
            if (sinkFails.get()) throw new IOException("sink not ready");
            plans.add(plan);
        }, grants::add);
    }

    /** A TLS key pair like the dropbox's ({@code DDDTLSUtil.generateKeyPair}: EC P-256). */
    private static KeyPair tlsKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] inventory(long seq) throws Exception {
        return DropboxControlCodec.encode(new InventoryReport(seq, 0, 1000, 0, null, List.of(), List.of(),
                                                              List.of()));
    }

    private static byte[] plan(long seq, long basedOn, List<RetentionPlan.Deletion> up) throws Exception {
        return DropboxControlCodec.encode(new RetentionPlan(seq, basedOn, null, up, List.of(), null));
    }

    private Path work(String name) {
        return tmp.getRoot().toPath().resolve(name);
    }

    // ------------------------------------------------------------------------------------------ endpoint

    @Test
    public void classifyAgreesWithBundleCoreBundleIdEncryption() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        ClientSecurity cs = ClientSecurity.getInstance();
        assertEquals(cs.getClientID(), ep.clientId());

        String mine5 = cs.encryptBundleID(BundleIDGenerator.generateBundleID(ep.clientId(), 5,
                                                                              BundleIDGenerator.DOWNSTREAM));
        assertEquals(ControlChannel.Ownership.MINE_NEW, ep.classify(mine5));

        byte[] otherClient = new byte[20];
        otherClient[0] = 1;
        String foreign = cs.encryptBundleID(BundleIDGenerator.generateBundleID(
                Base64.getUrlEncoder().encodeToString(otherClient), 5, BundleIDGenerator.DOWNSTREAM));
        assertEquals(ControlChannel.Ownership.NOT_MINE, ep.classify(foreign));
        assertEquals(ControlChannel.Ownership.NOT_MINE, ep.classify("bm90LWEtYnVuZGxlLWlk"));
        assertEquals(ControlChannel.Ownership.NOT_MINE, ep.classify("!!"));
        assertEquals(ControlChannel.Ownership.NOT_MINE, ep.classify(""));
        // A server-made id decrypts to the same answer.
        server.receive(ep.prepareUpstream(inventory(1)).path(), work("w")); // gives the server our keys
        String serverMade = server.security.createEncryptedBundleId(ep.clientId(), 3, BundleIDGenerator.DOWNSTREAM);
        assertEquals(ControlChannel.Ownership.MINE_NEW, ep.classify(serverMade));
    }

    @Test
    public void upstreamBundleCarriesOnlyTheNewestInventoryAndIsReusedWhenNothingChanged() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        ep.prepareUpstream(inventory(1)); // never uploaded: superseded by the next snapshot
        ControlChannel.OwnBundle own = ep.prepareUpstream(inventory(2));
        BundleJarInfo info = BundleJarInfo.read(own.path());
        assertEquals(own.bundleId(), info.getBundleId());
        assertNotNull(info.getClientKeyFingerprint());

        ControlChannel.OwnBundle same = ep.prepareUpstream(null);
        assertEquals(own.bundleId(), same.bundleId());
        try (Stream<Path> toSend = Files.list(same.path().getParent())) {
            assertEquals(1, toSend.count()); // bundle-core never prunes to-send/; the endpoint does
        }

        Received r = server.receive(own.path(), work("w"));
        assertEquals(2, r.onlyInventory().getReportSeq());
        assertEquals("HB", r.ack);
        assertTrue(Files.isDirectory(keysA.serverRoot.resolve("Clients").resolve(ep.clientId())));
    }

    @Test
    public void planBundleIsProcessedOnceFromACopyAndReportsKeepFlowing() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        ControlChannel.OwnBundle own1 = ep.prepareUpstream(inventory(1));
        server.receive(own1.path(), work("w1"));
        byte[] planBytes = plan(1, 1, List.of(new RetentionPlan.Deletion("someUpstreamId", Reason.RECEIVED_BY_SERVER)));
        Path jar = server.send(ep.clientId(), 1, 1, planBytes, own1.bundleId(), work("out"));
        String id = BundleJarInfo.read(jar).getBundleId();

        assertEquals(ControlChannel.Ownership.MINE_NEW, ep.classify(id));
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(jar, "transport-T1"));
        assertTrue("the caller's file is untouched", Files.exists(jar));
        assertEquals(List.of(DropboxControlCodec.decodePlan(planBytes)), plans);

        // Re-offered by a carrier: recognized as already processed, never decrypted or delivered again.
        assertEquals(ControlChannel.Ownership.MINE_ALREADY_PROCESSED, ep.classify(id));
        assertEquals(DropboxControlEndpoint.AcceptResult.ALREADY_PROCESSED, ep.accept(jar, "transport-T2"));
        assertEquals(1, plans.size());
        assertEquals(List.of(), receivedControlAduFiles());
        try (Stream<Path> leftovers = Files.list(root.resolve("BundleTransmission").resolve("bundle-generation")
                                                         .resolve("received-processing"))) {
            assertEquals(0, leftovers.count());
        }

        // The plan bundle listed only "net.discdd.other"; the backstop keeps the control app id registered, and the
        // new bundle acks the plan bundle.
        ControlChannel.OwnBundle own2 = ep.prepareUpstream(inventory(2));
        assertNotEquals(own1.bundleId(), own2.bundleId());
        Received r = server.receive(own2.path(), work("w2"));
        assertEquals(2, r.onlyInventory().getReportSeq());
        assertEquals(id, r.ack);
    }

    @Test
    public void aPlanTheSinkCouldNotApplyIsReplayedLaterExactlyOnce() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        ControlChannel.OwnBundle own = ep.prepareUpstream(inventory(1));
        server.receive(own.path(), work("w"));
        Path jar = server.send(ep.clientId(), 1, 1, plan(1, 1, List.of()), own.bundleId(), work("out"));

        sinkFails.set(true);
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(jar, null));
        assertTrue(plans.isEmpty());
        assertEquals(1, receivedControlAduFiles().size()); // kept for replay

        sinkFails.set(false);
        ep.replayPending();
        assertEquals(1, plans.size());
        ep.replayPending();
        assertEquals(1, plans.size());
        assertEquals(List.of(), receivedControlAduFiles());
    }

    @Test
    public void blankLargestReceivedFileIsRepairedToZero() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("db").resolve("LARGEST_BUNDLE_ID_RECEIVED.txt");
        DropboxControlEndpoint.repairBlankLargestReceived(f); // missing
        assertEquals("0", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        Files.write(f, "  \n".getBytes(StandardCharsets.UTF_8));
        DropboxControlEndpoint.repairBlankLargestReceived(f);
        assertEquals("0", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        Files.write(f, "someId".getBytes(StandardCharsets.UTF_8));
        DropboxControlEndpoint.repairBlankLargestReceived(f);
        assertEquals("someId", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));

        // In place: a truncated file must not wedge the endpoint (bundle-core would reject every reply).
        DropboxControlEndpoint ep = open(keysA);
        ControlChannel.OwnBundle own = ep.prepareUpstream(inventory(1));
        server.receive(own.path(), work("w"));
        Files.write(root.resolve("Shared").resolve("DB").resolve("LARGEST_BUNDLE_ID_RECEIVED.txt"), "".getBytes(StandardCharsets.UTF_8));
        Path jar = server.send(ep.clientId(), 1, 1, plan(1, 1, List.of()), own.bundleId(), work("out"));
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(jar, null));
        assertEquals(1, plans.size());
    }

    @Test
    public void changedServerKeysStartAFreshIdentityAndUnchangedKeysKeepIt() throws Exception {
        DropboxControlEndpoint a = open(keysA);
        String idA = a.clientId();
        assertFalse(a.wasResetForNewServer());

        DropboxControlEndpoint again = open(keysA);
        assertEquals(idA, again.clientId());
        assertFalse(again.wasResetForNewServer());

        DropboxControlEndpoint b = open(keysB);
        assertTrue(b.wasResetForNewServer());
        assertNotEquals(idA, b.clientId());
        assertEquals(b.clientId(), ClientSecurity.getInstance().getClientID());
    }

    /** A crash part-way through a wipe must not leave old-server state behind a "fresh" endpoint (review v2). */
    @Test
    public void anInterruptedWipeIsFinishedOnTheNextOpen() throws Exception {
        String idA = open(keysA).clientId();
        // the crash: marker written, server keys already gone, the rest still present
        Files.createFile(root.resolve(DropboxControlEndpoint.WIPE_MARKER));
        deleteTree(root.resolve(SecurityUtils.BUNDLE_SECURITY_DIR).resolve(SecurityUtils.SERVER_KEY_PATH));

        DropboxControlEndpoint b = open(keysB);
        assertTrue(b.wasResetForNewServer());
        assertNotEquals(idA, b.clientId());
        assertFalse(Files.exists(root.resolve(DropboxControlEndpoint.WIPE_MARKER)));
    }

    /** Endpoint state without server keys (an interrupted wipe from before the marker) is not "fresh". */
    @Test
    public void stateWithoutServerKeysIsWiped() throws Exception {
        open(keysA);
        deleteTree(root.resolve(SecurityUtils.BUNDLE_SECURITY_DIR).resolve(SecurityUtils.SERVER_KEY_PATH));
        assertTrue(open(keysA).wasResetForNewServer());
    }

    /** A truncated routing.metadata (bundle-core's truncating writer, then a crash) must not wedge the endpoint. */
    @Test
    public void truncatedRoutingMetadataIsRepaired() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        ep.prepareUpstream(inventory(1));
        Path metadata = root.resolve("BundleRouting").resolve("routing.metadata");
        Files.write(metadata, "{\"transport-1\": 4".getBytes(StandardCharsets.UTF_8));

        assertNotNull(ep.prepareUpstream(inventory(2)));
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata.toFile()); // parses again
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(p);
        }
    }

    @Test
    public void aSecondRootInTheSameProcessIsRefused() throws Exception {
        open(keysA);
        assertThrows(IllegalStateException.class,
                     () -> DropboxControlEndpoint.open(tmp.newFolder("other").toPath(), keysA.identityPem,
                                                       keysA.signedPrePem, keysA.ratchetPem, p -> {}));
    }

    /** A recency request and its grant travel under their own app id, never mixed with plans and inventories. */
    @Test
    public void recencyRequestAndGrantUseTheirOwnQueue() throws Exception {
        DropboxControlEndpoint ep = open(keysA);
        KeyPair tls = tlsKeyPair();
        RecencyRequest request = RecencyRequestSigner.sign(ep.clientId(), 1, tls);
        ControlChannel.OwnBundle own = ep.prepareUpstream(inventory(1), DropboxControlCodec.encode(request));
        Received r = server.receive(own.path(), work("w"));
        assertEquals(1, r.onlyInventory().getReportSeq());
        assertEquals(request, r.onlyRecencyRequest());
        assertTrue("the server can verify it for the bundle's client id",
                   RecencyRequestSigner.verify(ep.clientId(), r.onlyRecencyRequest()));
        assertEquals(own.bundleId(), ep.prepareUpstream(null, null).bundleId());

        RecencyGrant grant = RecencyGrant.of(1, server.mint("sender", 5).toByteArray(), 7L);
        Path grantJar = server.send(DropboxControl.RECENCY_APP_ID, ep.clientId(), 1, 1,
                                    DropboxControlCodec.encode(grant), own.bundleId(), work("g"));
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(grantJar, null));
        assertEquals(List.of(grant), grants);
        assertTrue(plans.isEmpty());

        // A message of the other channel's type on either queue is dropped and never blocks the queue.
        Path planOnRecency = server.send(DropboxControl.RECENCY_APP_ID, ep.clientId(), 2, 2, plan(1, 1, List.of()),
                                         own.bundleId(), work("p"));
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(planOnRecency, null));
        Path grantOnPlans = server.send(DropboxControl.APP_ID, ep.clientId(), 3, 1, DropboxControlCodec.encode(grant),
                                        own.bundleId(), work("q"));
        assertEquals(DropboxControlEndpoint.AcceptResult.PROCESSED, ep.accept(grantOnPlans, null));
        assertEquals(1, grants.size());
        assertTrue(plans.isEmpty());
        assertEquals(List.of(), receivedControlAduFiles());
        assertEquals(List.of(), receivedAduFiles(DropboxControl.RECENCY_APP_ID));

        // The grant bundle acknowledged the bundle that carried the request, so it no longer rides along.
        Received next = server.receive(ep.prepareUpstream(null, null).path(), work("w2"));
        assertTrue(next.recencyAdus.isEmpty());
    }

    private List<Path> receivedControlAduFiles() throws IOException {
        return receivedAduFiles(DropboxControl.APP_ID);
    }

    private List<Path> receivedAduFiles(String appId) throws IOException {
        Path dir = root.resolve("receive").resolve(appId);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().equals("metadata.json")).collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------------------------------ manager

    /** Records manager callbacks. */
    private static final class Events implements DropboxControlManager.Listener {
        final List<PlanExecutor.Outcome> outcomes = new ArrayList<>();
        final List<Long> reports = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        final List<Long> recencyRequests = new ArrayList<>();
        final List<String> recencyGrants = new ArrayList<>();

        @Override public void planApplied(PlanExecutor.Outcome o) { outcomes.add(o); }
        @Override public void recencyRequested(long seq) { recencyRequests.add(seq); }
        @Override public void recencyGranted(long seq, boolean stored) { recencyGrants.add(seq + ":" + stored); }
        @Override public void reportQueued(long seq, int up, int down) { reports.add(seq); }
        @Override public void problem(String message) { problems.add(message); }
        @Override public void statusChanged() {}
    }

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private DropboxControlManager manager(FileRelayStore store, Events events, AtomicInteger afterDeletions) {
        return new DropboxControlManager(root, store, List::of,
                                         afterDeletions::incrementAndGet, () -> 5_000_000L, events);
    }

    /** A realistic large inventory without writing twenty thousand jar files to the test filesystem. */
    private static final class LargeInventoryStore implements RelayStore {
        private final FileRelayStore delegate;
        private final List<BundleRecord> upstream = new ArrayList<>();

        LargeInventoryStore(FileRelayStore delegate) {
            this.delegate = delegate;
            for (int i = 0; i < 20_001; i++) {
                upstream.add(new BundleRecord(String.format("u%05d", i), Direction.UPSTREAM, 1, 1));
            }
        }

        @Override public Staged beginStaging(Direction d) throws IOException { return delegate.beginStaging(d); }
        @Override public BundleRecord commit(Staged s, String id) throws IOException { return delegate.commit(s, id); }
        @Override public void abort(Staged s) { delegate.abort(s); }
        @Override public List<BundleRecord> list(Direction d) {
            return d == Direction.UPSTREAM ? List.copyOf(upstream) : delegate.list(d);
        }
        @Override public Path pathFor(Direction d, String id) { return delegate.pathFor(d, id); }
        @Override public boolean contains(Direction d, String id) {
            return d == Direction.UPSTREAM ? upstream.stream().anyMatch(r -> r.bundleId().equals(id)) :
                    delegate.contains(d, id);
        }
        @Override public boolean touch(Direction d, String id) { return delegate.touch(d, id); }
        @Override public StoreStats stats() {
            StoreStats actual = delegate.stats();
            return new StoreStats(upstream.size(), upstream.size(), actual.downstreamCount(),
                                  actual.downstreamBytes(), actual.stagedBytes(), actual.capacityBytes());
        }
        @Override public boolean hasCapacityFor(Direction d, long bytes) {
            return delegate.hasCapacityFor(d, bytes);
        }
        @Override public void delete(Direction d, String id) {
            if (d == Direction.UPSTREAM) upstream.removeIf(r -> r.bundleId().equals(id));
            else delegate.delete(d, id);
        }
    }

    @Test
    public void managerKeepsPendingPageThroughRefreshAndRestartUntilDelayedPlanArrives() throws Exception {
        LargeInventoryStore store = new LargeInventoryStore(ControlFixtures.newStore(tmp.newFolder("large").toPath(),
                                                                                    clock));
        Events events = new Events();
        AtomicLong visitTime = new AtomicLong(5_000_000L);
        DropboxControlManager m = new DropboxControlManager(root, store, List::of, () -> {}, visitTime::get, events);
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));

        ControlChannel.OwnBundle first = m.ownBundleForVisit();
        Received firstReceived = server.receive(first.path(), work("large-first"));
        InventoryReport firstReport = firstReceived.onlyInventory();
        assertEquals(20_000, firstReport.getUpstream().size());
        assertEquals("u19999", firstReport.getUpstream().get(19_999).getBundleId());
        assertFalse(InventoryCompleteness.isComplete(firstReceived.controlAdus.get(0)));
        assertEquals(first.bundleId(), m.ownBundleForVisit().bundleId());
        assertEquals(List.of(1L), events.reports);

        visitTime.addAndGet(DropboxControlManager.REPORT_REFRESH_MS + 1);
        ControlChannel.OwnBundle refreshed = m.ownBundleForVisit();
        Received refreshedReceived = server.receive(refreshed.path(), work("large-refresh"));
        InventoryReport refreshedReport = refreshedReceived.onlyInventory();
        assertEquals(2, refreshedReport.getReportSeq());
        assertEquals("u19999", refreshedReport.getUpstream().get(19_999).getBundleId());
        assertEquals(List.of(1L, 2L), events.reports);

        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        assertEquals(refreshed.bundleId(), m.ownBundleForVisit().bundleId());
        assertEquals(List.of(1L, 2L), events.reports);

        Path delayedPlan = server.send(m.status().endpointClientId(), 1, 1,
                                       plan(1, firstReport.getReportSeq(), List.of()), refreshed.bundleId(),
                                       work("large-plan"));
        assertTrue(m.accept(delayedPlan, "transport-T1"));
        ControlChannel.OwnBundle tail = m.ownBundleForVisit();
        InventoryReport tailReport = server.receive(tail.path(), work("large-tail")).onlyInventory();
        assertEquals(3, tailReport.getReportSeq());
        assertEquals(List.of("u20000"), tailReport.getUpstream().stream()
                .map(InventoryReport.UpstreamEntry::getBundleId).collect(Collectors.toList()));
    }

    @Test
    public void managerRunsTheFullInventoryPlanLoopAgainstTheRelayStore() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        ECKeyPair client = Curve.generateKeyPair();
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 100, client.getPublicKey());
        ControlFixtures.commit(store, Direction.DOWNSTREAM, "d1", 100, client.getPublicKey());
        Events events = new Events();
        AtomicInteger afterDeletions = new AtomicInteger();
        DropboxControlManager m = manager(store, events, afterDeletions);

        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        assertTrue(m.status().available());
        String clientId = m.status().endpointClientId();
        assertNotNull(clientId);

        ControlChannel.OwnBundle own1 = m.ownBundleForVisit();
        assertEquals(List.of(1L), events.reports);
        assertEquals(own1.bundleId(), m.ownBundleForVisit().bundleId()); // unchanged: no new report, same bundle
        assertEquals(List.of(1L), events.reports);

        InventoryReport inv1 = server.receive(own1.path(), work("w1")).onlyInventory();
        assertEquals(List.of("u1"), inv1.getUpstream().stream().map(InventoryReport.UpstreamEntry::getBundleId)
                .collect(Collectors.toList()));
        assertEquals(BundleJarInfo.clientKeyFingerprint(client.getPublicKey().serialize()),
                     inv1.getUpstream().get(0).getClientKeyB64());
        assertEquals(1, inv1.getDownstream().size());

        Path planJar = server.send(clientId, 1, 1, plan(1, inv1.getReportSeq(),
                                                       List.of(new RetentionPlan.Deletion("u1",
                                                                                          Reason.RECEIVED_BY_SERVER))),
                                   own1.bundleId(), work("out"));
        String planId = BundleJarInfo.read(planJar).getBundleId();
        assertEquals(ControlChannel.Ownership.MINE_NEW, m.classify(planId));
        assertTrue(m.accept(planJar, "transport-T1"));

        assertFalse(store.contains(Direction.UPSTREAM, "u1"));
        assertTrue(store.contains(Direction.DOWNSTREAM, "d1"));
        assertEquals(1, afterDeletions.get());
        assertEquals(1, events.outcomes.size());
        assertEquals(1, m.status().lastPlanSeq());
        assertEquals(1, m.status().deletedUpstreamCount());
        assertEquals(5_000_000L, m.status().lastPlanAtMillis());

        ControlChannel.OwnBundle own2 = m.ownBundleForVisit();
        assertNotEquals(own1.bundleId(), own2.bundleId());
        Received r2 = server.receive(own2.path(), work("w2"));
        InventoryReport inv2 = r2.onlyInventory();
        assertEquals(planId, r2.ack);
        assertEquals(2, inv2.getReportSeq());
        assertEquals(1, inv2.getLastAppliedPlanSeq());
        assertTrue(inv2.getUpstream().isEmpty());
        assertEquals(List.of("u1"), inv2.getPlanResults().get(0).getDeleted());
        assertEquals(2, m.status().lastReportSeq());

        // The server answers report 2 with an empty plan (it answers every report). Applying it must not queue a
        // third report, or every plan would trigger a report and every report a plan, forever.
        Path emptyPlanJar = server.send(clientId, 2, 2, plan(2, inv2.getReportSeq(), List.of()), own2.bundleId(),
                                        work("out2"));
        assertTrue(m.accept(emptyPlanJar, "transport-T1"));
        assertEquals(2, m.status().lastPlanSeq());
        m.ownBundleForVisit();
        assertEquals(List.of(1L, 2L), events.reports);
        assertTrue(events.problems.isEmpty());
    }

    @Test
    public void managerAdoptsOwnBundlesFoundInThePickupQueue() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        Events events = new Events();
        DropboxControlManager m = manager(store, events, new AtomicInteger());
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        ControlChannel.OwnBundle own = m.ownBundleForVisit();
        server.receive(own.path(), work("w"));
        Path planJar = server.send(m.status().endpointClientId(), 1, 1,
                                   plan(1, 1, List.of(new RetentionPlan.Deletion("u1", Reason.SUPERSEDED))),
                                   own.bundleId(), work("out"));
        // As if an older build had relayed the dropbox's own plan bundle as a pickup.
        net.discdd.bundledropbox.store.Staged s = store.beginStaging(Direction.DOWNSTREAM);
        try (OutputStream out = s.open()) {
            Files.copy(planJar, out);
        }
        BundleRecord adopted = store.commit(s, null);

        assertEquals(1, m.adoptOwnBundles());
        assertFalse(store.contains(Direction.DOWNSTREAM, adopted.bundleId()));
        assertFalse(store.contains(Direction.UPSTREAM, "u1"));
        assertEquals(1, m.status().lastPlanSeq());
        assertEquals(0, m.adoptOwnBundles());
    }

    @Test
    public void managerRetainsOwnBundleWhenAdoptionFailsSoItCanRetry() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        Events events = new Events();
        DropboxControlManager m = manager(store, events, new AtomicInteger());
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        ControlChannel.OwnBundle own = m.ownBundleForVisit();
        server.receive(own.path(), work("w"));
        Path planJar = server.send(m.status().endpointClientId(), 1, 1,
                                   plan(1, 1, List.of(new RetentionPlan.Deletion("u1", Reason.SUPERSEDED))),
                                   own.bundleId(), work("out"));
        net.discdd.bundledropbox.store.Staged staged = store.beginStaging(Direction.DOWNSTREAM);
        try (OutputStream out = staged.open()) {
            Files.copy(planJar, out);
        }
        BundleRecord adopted = store.commit(staged, null);

        // Make the real accept path fail at its scratch-file copy, then restore the directory for retry.
        Path incoming = root.resolve(DropboxControlEndpoint.CONTROL_DIR_NAME).resolve("incoming");
        Path displacedIncoming = incoming.resolveSibling("incoming.saved");
        Files.move(incoming, displacedIncoming);
        Files.write(incoming, new byte[]{1});
        try {
            assertEquals(0, m.adoptOwnBundles());
            assertTrue("failed control bundle must remain available", store.contains(Direction.DOWNSTREAM,
                                                                                      adopted.bundleId()));
            assertEquals("failed acceptance must not apply the plan", 0, m.status().lastPlanSeq());
        } finally {
            Files.delete(incoming);
            Files.move(displacedIncoming, incoming);
        }

        assertEquals(1, m.adoptOwnBundles());
        assertFalse(store.contains(Direction.DOWNSTREAM, adopted.bundleId()));
        assertFalse(store.contains(Direction.UPSTREAM, "u1"));
        assertEquals(1, m.status().lastPlanSeq());
        assertEquals("successful adoption is replay-safe", 0, m.adoptOwnBundles());
    }

    /**
     * 09 Own Recency Blob Design, end to end: request out, grant in, next request out. A grant whose blob was minted
     * for another sender id (a carrier's) answers the request but is not stored; one minted for the dropbox's own
     * sender id is stored. An outstanding request never churns the own bundle id and survives a reopen.
     */
    @Test
    public void managerRunsTheRecencyRequestGrantLoop() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        KeyPair tls = tlsKeyPair();
        String ownSender = RecencyRequestSigner.senderIdFor(Base64.getEncoder().encodeToString(
                tls.getPublic().getEncoded()));
        long now = System.currentTimeMillis();
        FileRecencyBlobStore recency =
                new FileRecencyBlobStore(tmp.newFolder("meta").toPath().resolve("recencyBlob.bin"),
                                         new ServerTrust(server.security.getIdentityPublicKey().getPublicKey(),
                                                         () -> now), ownSender);
        Events events = new Events();
        AtomicLong visitTime = new AtomicLong(5_000_000L);
        DropboxControlManager m = new DropboxControlManager(root, store, List::of, () -> {}, visitTime::get, events,
                                                            recency, tls);
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        String clientId = m.status().endpointClientId();

        // Visit 1: the first own bundle carries request #1, signed for this endpoint with the TLS key.
        ControlChannel.OwnBundle own1 = m.ownBundleForVisit();
        RecencyRequest request1 = server.receive(own1.path(), work("w1")).onlyRecencyRequest();
        assertEquals(1, request1.getRequestSeq());
        assertTrue(RecencyRequestSigner.verify(clientId, request1));
        assertEquals(ownSender, RecencyRequestSigner.senderIdFor(request1.getTlsPublicKey()));
        assertTrue(m.status().recencyOutstanding());
        assertEquals(5_000_000L, m.status().recencyRequestedAtMillis());
        assertEquals(List.of(1L), events.recencyRequests);

        // While it is outstanding the bundle id is not churned, not even across a restart.
        assertEquals(own1.bundleId(), m.ownBundleForVisit().bundleId());
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        assertTrue(m.status().recencyOutstanding());
        assertEquals(own1.bundleId(), m.ownBundleForVisit().bundleId());
        assertEquals(List.of(1L), events.recencyRequests);

        // The server answers with a blob minted for another sender (a carrier's): it answers, but is not stored.
        RecencyGrant foreign = RecencyGrant.of(1, server.mint("carrier-sender", now - 2_000).toByteArray(), now);
        Path foreignJar = server.send(DropboxControl.RECENCY_APP_ID, clientId, 1, 1,
                                      DropboxControlCodec.encode(foreign), own1.bundleId(), work("g1"));
        assertTrue(m.accept(foreignJar, "transport-T1"));
        assertFalse(recency.current().isPresent());
        assertFalse(m.status().recencyOutstanding());
        assertEquals(List.of("1:false"), events.recencyGrants);

        // Visit 2: request #2 forces a new own bundle; the backstop kept the recency app id registered although
        // the grant bundle listed only another app.
        ControlChannel.OwnBundle own2 = m.ownBundleForVisit();
        assertNotEquals(own1.bundleId(), own2.bundleId());
        RecencyRequest request2 = server.receive(own2.path(), work("w2")).onlyRecencyRequest();
        assertEquals(2, request2.getRequestSeq());
        assertTrue(RecencyRequestSigner.verify(clientId, request2));

        // The grant for our own sender id is stored and served from now on.
        GetRecencyBlobResponse mine = server.mint(ownSender, now - 1_000);
        Path grantJar = server.send(DropboxControl.RECENCY_APP_ID, clientId, 2, 2,
                                    DropboxControlCodec.encode(RecencyGrant.of(2, mine.toByteArray(), now)),
                                    own2.bundleId(), work("g2"));
        assertTrue(m.accept(grantJar, "transport-T1"));
        assertArrayEquals(mine.toByteArray(), recency.current().get());
        assertEquals(now - 1_000, recency.currentTimestamp());
        assertEquals(2, m.status().recencyAnsweredSeq());
        assertEquals(List.of("1:false", "2:true"), events.recencyGrants);

        // Next visit: the next request goes out, as a transport fetches a fresh blob on every server sync.
        ControlChannel.OwnBundle own3 = m.ownBundleForVisit();
        assertEquals(3, server.receive(own3.path(), work("w3")).onlyRecencyRequest().getRequestSeq());
        assertEquals(List.of(1L, 2L, 3L), events.recencyRequests);
        assertTrue(events.problems.isEmpty());

        // Other server keys: a fresh endpoint, whose request numbering starts over.
        assertTrue(m.open("other", keysB.identityPem, keysB.signedPrePem, keysB.ratchetPem));
        assertTrue(m.wasResetForNewServer());
        assertEquals(0, m.status().recencyRequestSeq());
        assertFalse(m.status().recencyOutstanding());
    }

    @Test
    public void abandoningAnOutstandingRequestSendsTheNextOne() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        KeyPair tls = tlsKeyPair();
        FileRecencyBlobStore recency =
                new FileRecencyBlobStore(tmp.newFolder("meta").toPath().resolve("recencyBlob.bin"),
                                         new ServerTrust(server.security.getIdentityPublicKey().getPublicKey(),
                                                         System::currentTimeMillis), "own");
        Events events = new Events();
        DropboxControlManager m = new DropboxControlManager(root, store, List::of, () -> {}, () -> 5_000_000L,
                                                            events, recency, tls);
        assertTrue(m.open("built-in", keysA.identityPem, keysA.signedPrePem, keysA.ratchetPem));
        ControlChannel.OwnBundle own1 = m.ownBundleForVisit();
        assertTrue(m.status().recencyOutstanding());

        m.abandonRecencyRequest();
        assertFalse(m.status().recencyOutstanding());
        assertEquals("abandoned, never answered", 0, m.status().recencyGrantAtMillis());
        assertEquals(5_000_000L, m.status().recencyAbandonedAtMillis());
        ControlChannel.OwnBundle own2 = m.ownBundleForVisit();
        assertNotEquals(own1.bundleId(), own2.bundleId());
        assertEquals("a newer request supersedes the unsent one", 2,
                     server.receive(own2.path(), work("w")).onlyRecencyRequest().getRequestSeq());
    }

    @Test
    public void managerWithUnusableKeysFallsBackToRelayOnly() throws Exception {
        FileRelayStore store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        Events events = new Events();
        DropboxControlManager m = manager(store, events, new AtomicInteger());
        byte[] junk = "not a key".getBytes(StandardCharsets.UTF_8);

        assertFalse(m.open("broken", junk, junk, junk));

        assertFalse(m.status().available());
        assertNotNull(m.status().problem());
        assertEquals("broken", m.status().serverKeys());
        assertNull(m.ownBundleForVisit());
        assertEquals(ControlChannel.Ownership.NOT_MINE, m.classify("anything"));
        assertEquals(1, events.problems.size());

        assertTrue("good keys recover", m.open("built-in", keysA.identityPem, keysA.signedPrePem,
                                                keysA.ratchetPem));
        assertTrue(m.status().available());
    }
}
