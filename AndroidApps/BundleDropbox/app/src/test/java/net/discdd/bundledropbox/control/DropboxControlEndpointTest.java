package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.ServerPlanRetentionPolicy;
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
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.model.ADU;
import net.discdd.model.Bundle;
import net.discdd.model.UncompressedBundle;
import net.discdd.utils.BundleUtils;
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
 * relay-store deletions, results in the next inventory, start-up adoption).
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
                    if (name.equals("acknowledgement.txt")) r.ack = new String(bytes, StandardCharsets.UTF_8).trim();
                }
            }
            return r;
        }

        /** Builds downstream bundle {@code counter} for {@code clientId} carrying one control ADU. */
        Path send(String clientId, long counter, long aduId, byte[] aduBytes, String ackedId, Path outDir)
                throws Exception {
            Files.createDirectories(outDir);
            String encryptedId = security.createEncryptedBundleId(clientId, counter, BundleIDGenerator.DOWNSTREAM);
            Path aduFile = outDir.resolve("adu-" + counter);
            Files.write(aduFile, aduBytes);
            ADU adu = new ADU(aduFile.toFile(), DropboxControl.APP_ID, aduId, aduBytes.length);
            PipedInputStream pipe = new PipedInputStream();
            // app_ids.txt deliberately WITHOUT the control app id: the endpoint's backstop must keep reports flowing.
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
    }

    private static final class Received {
        final List<byte[]> controlAdus = new ArrayList<>();
        String ack;

        InventoryReport onlyInventory() throws Exception {
            assertEquals(1, controlAdus.size());
            return DropboxControlCodec.decodeInventory(controlAdus.get(0));
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
        });
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

    @Test
    public void aSecondRootInTheSameProcessIsRefused() throws Exception {
        open(keysA);
        assertThrows(IllegalStateException.class,
                     () -> DropboxControlEndpoint.open(tmp.newFolder("other").toPath(), keysA.identityPem,
                                                       keysA.signedPrePem, keysA.ratchetPem, p -> {}));
    }

    private List<Path> receivedControlAduFiles() throws IOException {
        Path dir = root.resolve("receive").resolve(DropboxControl.APP_ID);
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

        @Override public void planApplied(PlanExecutor.Outcome o) { outcomes.add(o); }
        @Override public void reportQueued(long seq, int up, int down) { reports.add(seq); }
        @Override public void problem(String message) { problems.add(message); }
        @Override public void statusChanged() {}
    }

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private DropboxControlManager manager(FileRelayStore store, Events events, AtomicInteger afterDeletions) {
        return new DropboxControlManager(root, store, List::of, ServerPlanRetentionPolicy.INSTANCE,
                                         afterDeletions::incrementAndGet, () -> 5_000_000L, events);
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
