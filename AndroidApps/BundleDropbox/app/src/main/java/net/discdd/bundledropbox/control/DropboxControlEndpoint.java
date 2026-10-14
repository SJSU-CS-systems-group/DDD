package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.visit.ControlChannel;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.DropboxControlException;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.model.ADU;
import net.discdd.model.Bundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.StoreADUs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPrivateKey;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The dropbox's own DDD client endpoint: one bundle-core {@link ClientBundleTransmission} with its own client
 * keys, through which the dropbox talks to the server about its storage (06 Storage Management Design,
 * Research/R7) and gets its own recency blob (09 Own Recency Blob Design). It sends {@code inventory} ADUs and
 * receives {@code plan} ADUs under {@link DropboxControl#APP_ID}, and sends {@code recencyRequest} ADUs and
 * receives {@code recencyGrant} ADUs under {@link DropboxControl#RECENCY_APP_ID}. The two app ids are separate
 * queues because the server keeps exactly one plan queued under {@code APP_ID} and decodes it as a plan. The
 * bundles travel through carriers exactly like a stock client's.
 *
 * <p>No network: the endpoint only builds and consumes bundle files. The visit uploads its bundle and hands it
 * downloaded bundles ({@code DropboxControlManager}, {@link ControlChannel}).
 *
 * <h2>Process-wide singletons (Research/R7 §1)</h2>
 * bundle-core keeps {@code ClientSecurity} and {@code ClientRouting} as per-process singletons that keep their
 * first {@code ClientPaths}. So there is <b>one endpoint root per process</b> ({@link #open} refuses a second
 * root), and re-opening (new server keys) calls {@code ClientSecurity.resetInstance()} so the singleton re-reads
 * the key files from disk. JVM tests must use one root per test JVM.
 *
 * <h2>Rules around {@code processReceivedBundle} (R7 §4)</h2>
 * <ul>
 *   <li><b>Addressed-to-me by decryption, not the 10-id window.</b> {@link #classify} decrypts the jar's
 *       {@code bundle.id} with a cached AES key (option B in R7 §4.1). The window ({@code getNextBundles()}) wedges
 *       once the server's counter runs 10 past the largest received, which happens because its window never
 *       fills (R7 §2, §6).</li>
 *   <li><b>Pre-filter by counter.</b> Only a bundle whose counter is above the largest received is processed;
 *       bundle-core decrypts the payload <em>before</em> its own latest-id check, so feeding an old bundle would
 *       re-run Signal decryption on an old message.</li>
 *   <li><b>Pass a copy.</b> {@code processReceivedBundle} deletes its input file.</li>
 *   <li><b>Repair a blank largest-received file to {@code "0"}.</b> bundle-core rewrites it with a truncating
 *       writer; an empty file makes it reject every reply forever (R7 §7).</li>
 *   <li><b>Recreate a missing {@code routing.metadata}.</b> bundle-core's {@code ClientRouting} singleton writes it
 *       only once per process, so a re-created root (server-key change) would otherwise lack it.</li>
 *   <li><b>Union {@link DropboxControl#APP_ID} and {@link DropboxControl#RECENCY_APP_ID} into the registered app
 *       ids</b> at open and after each bundle: the client only sends app ids the server listed, so a server that
 *       omitted one would silence the inventory or the recency request (R7 §3). The server lists them for
 *       dropbox nodes too; this is the backstop.</li>
 * </ul>
 *
 * <h2>ADUs (Research/R6 §3.1)</h2>
 * Received plan and grant ADUs are read from the stored copy, handed to the {@link PlanSink} or the
 * {@link GrantSink}, and then deleted with {@code deleteAllFilesUpTo}, which also makes bundle-core skip
 * re-deliveries of the same ADU ids. An ADU whose sink failed stays stored and is replayed by
 * {@link #replayPending()} (at start-up and after the next bundle); the two app ids are drained independently, so
 * a stuck plan never holds back a grant or the other way round. A message of the wrong type for its app id is
 * dropped ({@code UNEXPECTED_TYPE}). Inventory and recency-request ADUs are snapshots: queuing a new one deletes
 * the older unsent ones of its app id, so only the newest rides.
 *
 * <p>Thread-safety: none of the bundle-core classes are thread-safe, so every method is {@code synchronized}.
 */
public final class DropboxControlEndpoint {
    private static final Logger logger = Logger.getLogger(DropboxControlEndpoint.class.getName());

    /** Sub-directory of the endpoint root for the dropbox's own control files (state, scratch copies). */
    public static final String CONTROL_DIR_NAME = "dropbox-control";

    /** Downstream bundle-id layout: 8-byte counter, then the client id bytes (BundleIDGenerator, DOWNSTREAM). */
    private static final int COUNTER_BYTES = 8;

    /** Receives each decoded plan, in ADU order. */
    @FunctionalInterface
    public interface PlanSink {
        /**
         * @param plan a decoded plan
         * @throws IOException to keep the plan's ADU stored and retry it later ({@link #replayPending()})
         */
        void onPlan(RetentionPlan plan) throws IOException;
    }

    /** Receives each decoded recency grant, in ADU order. */
    @FunctionalInterface
    public interface GrantSink {
        /**
         * @param grant a decoded grant (shape checked only: the blob is neither parsed nor verified yet)
         * @throws IOException to keep the grant's ADU stored and retry it later ({@link #replayPending()})
         */
        void onGrant(RecencyGrant grant) throws IOException;
    }

    /** A grant sink for endpoints that do not take part in the recency exchange (tests): grants are dropped. */
    static final GrantSink DROP_GRANTS =
            grant -> logger.log(Level.INFO, "No recency consumer; dropping " + grant);

    /** Outcome of {@link #accept}. */
    public enum AcceptResult {
        /** The bundle was processed: its ADUs were stored and delivered, and it is now the largest received. */
        PROCESSED,
        /** Not addressed to this endpoint. */
        NOT_MINE,
        /** Addressed to this endpoint but not newer than the largest received. */
        ALREADY_PROCESSED,
        /** bundle-core rejected it (logged); a carrier may offer it again. */
        FAILED
    }

    // Process-wide guard for bundle-core's singletons (see class Javadoc).
    private static Path processRoot; // guarded by DropboxControlEndpoint.class

    private final Path root;
    private final ClientPaths paths;
    private final ClientBundleTransmission transmission;
    private final PlanSink sink;
    private final GrantSink grantSink;
    private final String clientId;
    private final byte[] clientIdBytes;
    private final SecretKeySpec bundleIdKey;
    private final Path scratchDir;
    private final boolean resetForNewServer;
    private boolean closed; // guarded by this

    private DropboxControlEndpoint(Path root, ClientPaths paths, PlanSink sink, GrantSink grantSink,
                                   boolean resetForNewServer) throws Exception {
        this.root = root;
        this.paths = paths;
        this.sink = sink;
        this.grantSink = grantSink;
        this.resetForNewServer = resetForNewServer;
        this.scratchDir = Files.createDirectories(root.resolve(CONTROL_DIR_NAME).resolve("incoming"));
        this.transmission = new ClientBundleTransmission(paths, this::onAdu);
        ClientSecurity security = transmission.getBundleSecurity().getClientSecurity();
        this.clientId = security.getClientID();
        this.clientIdBytes = Base64.getUrlDecoder().decode(clientId);
        this.bundleIdKey = deriveBundleIdKey(security, paths);
        ensureControlAppsRegistered();
        cleanScratch();
    }

    /**
     * Opens (creating on first use) the endpoint rooted at {@code root}.
     *
     * <p>If {@code root} already holds an endpoint created for <em>different</em> server keys, it is wiped first:
     * its Signal session, client window and counters belong to the other server and would make every bundle
     * unusable (the new server would see a session it cannot read; downstream ids encrypted under the old key
     * could not be compared). The dropbox then appears to the new server as a new client, which it learns about
     * from the first bundle; no registration exists (R7 §2).
     *
     * <p>Received plans and grants are <b>not</b> replayed here: call {@link #replayPending()} once the sinks are
     * ready.
     *
     * @param root          {@code getDataDir()/ControlEndpoint} in production (internal storage, R7 §1)
     * @param identityPem   {@code server_identity.pub} text
     * @param signedPrePem  {@code server_signed_pre.pub} text
     * @param ratchetPem    {@code server_ratchet.pub} text
     * @param sink          receives decoded plans
     * @param grantSink     receives decoded recency grants
     * @return the open endpoint
     * @throws IllegalStateException if another root was opened in this process
     * @throws Exception             if bundle-core cannot initialize (bad keys, I/O)
     */
    public static DropboxControlEndpoint open(Path root, byte[] identityPem, byte[] signedPrePem, byte[] ratchetPem,
                                              PlanSink sink, GrantSink grantSink) throws Exception {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(grantSink, "grantSink");
        Path r = root.toAbsolutePath().normalize();
        synchronized (DropboxControlEndpoint.class) {
            if (processRoot != null && !processRoot.equals(r)) {
                throw new IllegalStateException("bundle-core allows one client endpoint per process; already " +
                                                        "opened " + processRoot + ", refusing " + r);
            }
            Files.createDirectories(r);
            boolean reset = needsWipe(r, identityPem, signedPrePem, ratchetPem);
            if (reset) {
                logger.log(Level.WARNING, "Server keys changed: starting a fresh control endpoint in " + r);
                wipeContents(r);
            }
            // Overwrites the three server key files whenever they are given (ClientPaths, R7 §1).
            ClientPaths paths = new ClientPaths(r, identityPem, signedPrePem, ratchetPem);
            repairBlankLargestReceived(paths.largestBundleIdReceived);
            ensureRoutingMetadata(paths);
            resetClientSecurityIfInitialized();
            DropboxControlEndpoint endpoint = new DropboxControlEndpoint(r, paths, sink, grantSink, reset);
            processRoot = r;
            logger.log(Level.INFO, "Control endpoint " + endpoint.clientId + " open at " + r);
            return endpoint;
        }
    }

    /**
     * {@link #open(Path, byte[], byte[], byte[], PlanSink, GrantSink)} for an endpoint without a recency consumer:
     * received grants are logged and dropped.
     */
    public static DropboxControlEndpoint open(Path root, byte[] identityPem, byte[] signedPrePem, byte[] ratchetPem,
                                              PlanSink sink) throws Exception {
        return open(root, identityPem, signedPrePem, ratchetPem, sink, DROP_GRANTS);
    }

    /**
     * @return this endpoint's DDD client id (Base64url SHA-1 of its identity key), as the server knows it. Also the
     *         {@code clientId} a recency request is signed for ({@code RecencyRequestSigner})
     */
    public String clientId() {
        return clientId;
    }

    /** @return true if {@link #open} wiped state left by a different server's keys */
    public boolean wasResetForNewServer() {
        return resetForNewServer;
    }

    /** @return the endpoint root */
    public Path root() {
        return root;
    }

    /** {@link #prepareUpstream(byte[], byte[])} without a recency request. */
    public synchronized ControlChannel.OwnBundle prepareUpstream(byte[] inventoryAdu) throws Exception {
        return prepareUpstream(inventoryAdu, null);
    }

    /**
     * Queues {@code inventoryAdu} and {@code recencyRequestAdu} (each if given) and returns the bundle to upload.
     *
     * <p>bundle-core reuses the newest generated bundle unless a newer ack or new ADUs exist
     * ({@code generateBundleForTransmission}), so passing {@code null} for both when nothing changed keeps
     * re-offering the same bundle id instead of using up a new one per visit; queuing either one forces a new
     * bundle. Queued ADUs stay in every new bundle until the server acknowledges a bundle that carried them, so a
     * request waiting for its grant still rides along without being queued again. Older generated bundles are
     * deleted: bundle-core never prunes {@code to-send/} itself (R7 §3).
     *
     * @param inventoryAdu      an encoded inventory to send under {@link DropboxControl#APP_ID}, replacing any older
     *                          unsent one; {@code null} to keep the queued ones as they are
     * @param recencyRequestAdu an encoded recency request to send under {@link DropboxControl#RECENCY_APP_ID},
     *                          replacing any older unsent one; {@code null} to keep the queued ones as they are
     * @return the bundle to upload
     */
    public synchronized ControlChannel.OwnBundle prepareUpstream(byte[] inventoryAdu, byte[] recencyRequestAdu)
            throws Exception {
        checkOpen();
        StoreADUs send = transmission.applicationDataManager.sendADUsStorage;
        if (inventoryAdu != null) queueSnapshot(send, DropboxControl.APP_ID, inventoryAdu);
        if (recencyRequestAdu != null) queueSnapshot(send, DropboxControl.RECENCY_APP_ID, recencyRequestAdu);
        // bundle-core detects new ADUs by comparing millisecond file times with strict ">", so an ADU queued in
        // the same millisecond as the previous bundle would be missed and that bundle re-offered without it; for
        // a recency request that would stall until something else changes. Whatever was queued must ride, so the
        // superseded bundle goes now rather than after generation (pruneToSend would delete it anyway).
        if (inventoryAdu != null || recencyRequestAdu != null) pruneToSend(null);
        ensureRoutingMetadata(paths);
        Bundle bundle = transmission.generateBundleForTransmission();
        Path file = bundle.getSource().toPath();
        pruneToSend(file);
        return new ControlChannel.OwnBundle(bundle.getBundleId(), file);
    }

    /** Adds {@code adu} under {@code appId} after deleting the older unsent ones: snapshots supersede each other. */
    private static void queueSnapshot(StoreADUs send, String appId, byte[] adu) throws IOException {
        long lastAdded = send.getLastADUIdAdded(null, appId);
        if (lastAdded > send.getLastADUIdDeleted(null, appId)) {
            // Every unacked ADU would otherwise ride in every bundle (R6 §3.1).
            send.deleteAllFilesUpTo(null, appId, lastAdded);
        }
        send.addADU(null, appId, adu, -1);
    }

    /**
     * Decides whether a downloaded bundle is addressed to this endpoint, without bundle-core and in one AES block
     * operation: the downstream id is {@code Base64url(AES-CBC(key, IV=0, Base64url(counter ‖ clientId)))} with a
     * key derived from the shared secret of the server identity key and this endpoint's identity key (R7 §4.1).
     * A foreign id fails the padding check or the client-id comparison.
     *
     * @param encryptedBundleId a jar's {@code bundle.id}
     * @return the ownership
     */
    public synchronized ControlChannel.Ownership classify(String encryptedBundleId) {
        checkOpen();
        long counter = decryptOwnCounter(encryptedBundleId);
        if (counter < 0) return ControlChannel.Ownership.NOT_MINE;
        return counter > largestReceivedCounter() ? ControlChannel.Ownership.MINE_NEW :
               ControlChannel.Ownership.MINE_ALREADY_PROCESSED;
    }

    /**
     * Processes a bundle addressed to this endpoint: copies it (bundle-core deletes its input), runs
     * {@code processReceivedBundle}, which stores its ADUs and delivers plans and grants to their sinks, then
     * cleans up the extracted files bundle-core leaves behind.
     *
     * @param jar             the downloaded jar (not modified or deleted)
     * @param carrierSenderId the carrier's recency-blob {@code senderId}; bundle-core adds it to this endpoint's
     *                        routing metadata, so the server can route replies via every carrier the dropbox met
     *                        (R7 §4). {@code null} if unknown
     * @return what happened
     */
    public synchronized AcceptResult accept(Path jar, String carrierSenderId) {
        checkOpen();
        String bundleId;
        try {
            bundleId = BundleJarInfo.read(jar).getBundleId();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Not a bundle jar: " + jar, e);
            return AcceptResult.FAILED;
        }
        ControlChannel.Ownership ownership = classify(bundleId);
        if (ownership == ControlChannel.Ownership.NOT_MINE) return AcceptResult.NOT_MINE;
        if (ownership == ControlChannel.Ownership.MINE_ALREADY_PROCESSED) return AcceptResult.ALREADY_PROCESSED;
        Path copy = scratchDir.resolve(bundleId); // extractBundle names its directory after the file
        try {
            repairBlankLargestReceived(paths.largestBundleIdReceived);
            ensureRoutingMetadata(paths);
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            transmission.processReceivedBundle(carrierSenderId, new Bundle(bundleId, copy.toFile()));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Control bundle " + bundleId + " could not be processed", e);
            return AcceptResult.FAILED;
        } finally {
            deleteQuietly(copy);
            deleteRecursively(paths.uncompressedPayloadStore.resolve(bundleId));
            ensureControlAppsRegistered();
        }
        boolean nowLargest = decryptOwnCounter(bundleId) == largestReceivedCounter();
        return nowLargest ? AcceptResult.PROCESSED : AcceptResult.FAILED;
    }

    /**
     * Delivers stored plan and grant ADUs that were not processed yet (a sink failure, or a crash between storing
     * and applying), then deletes them. Safe to call at any time.
     */
    public synchronized void replayPending() {
        checkOpen();
        drainReceived();
    }

    /** Marks the endpoint unusable; a later {@link #open} on the same root replaces it. */
    public synchronized void close() {
        closed = true;
    }

    // ------------------------------------------------------------------------------------------ ADUs

    /** bundle-core's ADU consumer: one call per stored ADU, then {@code null} at the end of each batch. */
    private void onAdu(ADU adu) {
        if (adu == null) drainReceived(); // read the stored copies once the whole batch is on disk
    }

    private void drainReceived() {
        drain(DropboxControl.APP_ID, "plan", bytes -> sink.onPlan(DropboxControlCodec.decodePlan(bytes)));
        drain(DropboxControl.RECENCY_APP_ID, "grant",
              bytes -> grantSink.onGrant(DropboxControlCodec.decodeRecencyGrant(bytes)));
    }

    /** Decodes and delivers one stored control ADU. */
    @FunctionalInterface
    private interface AduHandler {
        void handle(byte[] bytes) throws DropboxControlException, IOException;
    }

    /** Delivers the stored ADUs of {@code appId} in order and deletes those that are done. */
    private void drain(String appId, String what, AduHandler handler) {
        StoreADUs received = transmission.applicationDataManager.receiveADUsStorage;
        List<ADU> adus;
        try {
            adus = received.getAppData(null, appId);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot list received " + appId + " ADUs", e);
            return;
        }
        long done = -1;
        for (ADU adu : adus) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(adu.getSource().toPath());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Cannot read " + appId + " ADU " + adu.getADUId() + "; retrying later", e);
                break;
            }
            try {
                handler.handle(bytes);
            } catch (DropboxControlException e) {
                // Unknown or wrong type, or a malformed message: log and drop, as the contract says (it cannot get
                // better).
                logger.log(Level.WARNING, "Dropping " + appId + " ADU " + adu.getADUId() + ": " + e.getKind() + " " +
                        e.getMessage());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Control " + what + " ADU " + adu.getADUId() + " not applied yet; will retry",
                           e);
                break;
            } catch (RuntimeException e) {
                // A message that crashes its consumer would otherwise block every later one forever.
                logger.log(Level.SEVERE, "Dropping " + appId + " ADU " + adu.getADUId() + " after an error", e);
            }
            done = adu.getADUId();
        }
        if (done < 0) return;
        try {
            // Also raises lastAduDeleted, so bundle-core skips re-deliveries of these ids (R7 §4).
            received.deleteAllFilesUpTo(null, appId, done);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot delete processed " + appId + " ADUs up to " + done, e);
        }
    }

    /** Backstop for the server's app-id list (class Javadoc): both control app ids, kept in the server's order. */
    private void ensureControlAppsRegistered() {
        List<String> current = transmission.applicationDataManager.getRegisteredAppIds();
        List<String> ids = new ArrayList<>(current == null ? List.of() : current);
        boolean changed = false;
        for (String appId : List.of(DropboxControl.APP_ID, DropboxControl.RECENCY_APP_ID)) {
            if (!ids.contains(appId)) {
                ids.add(appId);
                changed = true;
            }
        }
        if (changed) transmission.applicationDataManager.setRegisteredAppIds(ids);
    }

    // ------------------------------------------------------------------------------------------ bundle ids

    /** @return the downstream counter if {@code encryptedBundleId} is addressed to this endpoint, else -1 */
    private long decryptOwnCounter(String encryptedBundleId) {
        if (encryptedBundleId == null || encryptedBundleId.isEmpty()) return -1;
        try {
            byte[] cipherText = Base64.getUrlDecoder().decode(encryptedBundleId.trim());
            if (cipherText.length == 0 || cipherText.length % 16 != 0) return -1;
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, bundleIdKey, new IvParameterSpec(new byte[16]));
            String plainId = new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
            byte[] raw = Base64.getUrlDecoder().decode(plainId);
            if (raw.length != COUNTER_BYTES + clientIdBytes.length) return -1;
            if (!Arrays.equals(Arrays.copyOfRange(raw, COUNTER_BYTES, raw.length), clientIdBytes)) return -1;
            long counter = ByteBuffer.wrap(raw, 0, COUNTER_BYTES).getLong();
            return counter < 0 ? -1 : counter;
        } catch (BadPaddingException | IllegalArgumentException e) {
            return -1; // someone else's bundle
        } catch (GeneralSecurityException e) {
            logger.log(Level.WARNING, "Bundle id decryption unavailable", e);
            return -1;
        }
    }

    /** @return the counter of the largest downstream id processed, 0 if none ({@code "0"} file) */
    private long largestReceivedCounter() {
        String largest;
        try {
            largest = new String(Files.readAllBytes(paths.largestBundleIdReceived), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return 0;
        }
        if (largest.isEmpty() || largest.equals("0")) return 0;
        long counter = decryptOwnCounter(largest);
        if (counter < 0) {
            logger.log(Level.WARNING, "Largest received bundle id is not ours: " + largest);
            return 0;
        }
        return counter;
    }

    /**
     * Derives the AES key bundle-core uses for this endpoint's bundle ids, once (each derivation is 65,536 PBKDF2
     * rounds): {@code ClientSecurity.decryptBundleID} = {@code SecurityUtils.decryptAesCbcPkcs5(secret, id, true)}
     * with {@code secret = Base64url(agreement(serverIdentity, clientIdentityPrivate))}. Pinned by a JVM test
     * against {@code ClientSecurity.encryptBundleID}.
     */
    private static SecretKeySpec deriveBundleIdKey(ClientSecurity security, ClientPaths paths) throws Exception {
        byte[] privateBytes = Files.readAllBytes(paths.clientKeyPath.resolve(SecurityUtils.CLIENT_IDENTITY_PRIVATE_KEY));
        ECPrivateKey identityPrivate = Curve.decodePrivatePoint(privateBytes);
        byte[] agreement = Curve.calculateAgreement(security.getServerPublicKey(), identityPrivate);
        String secret = Base64.getUrlEncoder().encodeToString(agreement);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), secret.getBytes(StandardCharsets.UTF_8),
                                         SecurityUtils.ITERATIONS, SecurityUtils.KEYLEN);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    // ------------------------------------------------------------------------------------------ files

    private void checkOpen() {
        if (closed) throw new IllegalStateException("control endpoint closed");
    }

    /**
     * Keeps only {@code keep} in {@code to-send/}: the reuse rule only ever looks at the newest file.
     *
     * @param keep the bundle to keep, or {@code null} to delete them all
     */
    private void pruneToSend(Path keep) {
        File[] files = paths.tosendDir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.toPath().equals(keep)) deleteQuietly(f.toPath());
        }
    }

    /** Removes scratch copies and extracted trees left by a crash in {@link #accept}. */
    private void cleanScratch() {
        for (Path dir : List.of(scratchDir, paths.uncompressedPayloadStore)) {
            try (Stream<Path> children = Files.list(dir)) {
                children.forEach(DropboxControlEndpoint::deleteRecursively);
            } catch (IOException e) {
                logger.log(Level.FINE, "Cannot clean " + dir, e);
            }
        }
    }

    /**
     * bundle-core writes the largest-received id with a truncating writer; an empty file makes it reject every
     * downstream bundle forever, and {@code ClientPaths} only creates the file when it is missing (R7 §7).
     */
    static void repairBlankLargestReceived(Path file) throws IOException {
        String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            content = "";
        }
        if (content.trim().isEmpty()) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, "0".getBytes(StandardCharsets.UTF_8));
            logger.log(Level.WARNING, "Repaired blank " + file.getFileName() + " to \"0\"");
        }
    }

    /**
     * Recreates {@code BundleRouting/routing.metadata} as an empty JSON map if it is missing, blank or not the map
     * bundle-core expects. bundle-core's {@code ClientRouting} is a process-wide singleton that writes this file
     * only when it is first constructed, and reads it on every bundle generation and every received bundle. Two
     * ways it goes bad: a server-key change wipes the endpoint root in the same process (the singleton survives,
     * the file does not), and bundle-core's truncating Jackson {@code writeValue} can leave a partial file after a
     * crash. Either makes every later bundle fail; worse, {@code processReceivedBundle} updates this file only
     * after Signal decryption, so each failed attempt also burns that bundle's Signal message (R7 §7). The file
     * holds only per-transport sighting counts, so resetting it loses nothing the server needs.
     */
    static void ensureRoutingMetadata(ClientPaths paths) throws IOException {
        Path file = paths.metadataFile.toPath();
        boolean valid;
        try {
            // the exact shape ClientRouting reads: a JSON object of numbers
            // ("null" parses to no map at all, which ClientRouting would then dereference)
            valid = new ObjectMapper().readValue(file.toFile(), new TypeReference<HashMap<String, Long>>() {}) != null;
        } catch (NoSuchFileException | FileNotFoundException e) {
            valid = false;
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, "Repairing unreadable " + file.getFileName() + ": " + e.getOriginalMessage());
            valid = false;
        }
        if (!valid) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Marker written before a wipe starts and removed when it is complete. A crash part-way through a wipe can
     * delete the server keys while state encrypted under the old server (the largest received id, the Signal
     * session) survives; that endpoint would look fresh and then fail every downstream bundle. With the marker,
     * the next {@link #open} finishes the interrupted wipe instead.
     */
    static final String WIPE_MARKER = ".wipe-pending";

    /**
     * @return true if {@code root} must be wiped: a wipe was interrupted, its server keys differ from the given
     *         ones, or it holds endpoint state but no server identity key (left by an interrupted wipe that
     *         predates the marker). An empty root is a fresh endpoint.
     * @throws IOException if the stored keys cannot be read; the endpoint then stays closed rather than wiping
     *                     the dropbox's identity over what may be a transient error
     */
    static boolean needsWipe(Path root, byte[] identity, byte[] signedPre, byte[] ratchet) throws IOException {
        if (Files.exists(root.resolve(WIPE_MARKER))) return true;
        Path dir = root.resolve(SecurityUtils.BUNDLE_SECURITY_DIR).resolve(SecurityUtils.SERVER_KEY_PATH);
        Path identityFile = dir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        if (!Files.exists(identityFile)) {
            try (Stream<Path> children = Files.list(root)) {
                return children.findAny().isPresent();
            }
        }
        return differs(identityFile, identity) || differs(dir.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY), signedPre) ||
                differs(dir.resolve(SecurityUtils.SERVER_RATCHET_KEY), ratchet);
    }

    private static boolean differs(Path existing, byte[] wanted) throws IOException {
        try {
            return !normalizePem(Files.readAllBytes(existing)).equals(normalizePem(wanted));
        } catch (NoSuchFileException e) {
            return true; // the identity key exists, so a missing sibling means the key set is incomplete
        }
    }

    /** PEM text with line endings and surrounding whitespace normalized (raw resources may end in CRLF). */
    private static String normalizePem(byte[] pem) {
        StringBuilder b = new StringBuilder();
        for (String line : new String(pem, StandardCharsets.UTF_8).split("\\R")) {
            if (!line.trim().isEmpty()) b.append(line.trim()).append('\n');
        }
        return b.toString();
    }

    /** Re-reads keys from disk in bundle-core's {@code ClientSecurity} singleton, if one exists. */
    private static void resetClientSecurityIfInitialized() throws Exception {
        try {
            ClientSecurity.getInstance();
        } catch (IllegalStateException notYet) {
            return;
        }
        ClientSecurity.resetInstance(); // same root (enforced), so the singleton's ClientPaths are still right
    }

    /** Deletes everything under {@code root}, bracketed by {@link #WIPE_MARKER} so a crash resumes the wipe. */
    static void wipeContents(Path root) throws IOException {
        Path marker = root.resolve(WIPE_MARKER);
        if (!Files.exists(marker)) Files.createFile(marker);
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!child.equals(marker)) deleteRecursively(child);
            }
        }
        try (Stream<Path> left = Files.list(root)) {
            if (left.anyMatch(p -> !p.equals(marker))) throw new IOException("could not wipe " + root);
        }
        Files.delete(marker);
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(DropboxControlEndpoint::deleteQuietly);
        } catch (IOException e) {
            logger.log(Level.FINE, "Cannot delete " + p, e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            logger.log(Level.FINE, "Cannot delete " + p, e);
        }
    }

    @Override
    public String toString() {
        return "DropboxControlEndpoint{" + clientId + " at " + root + "}";
    }
}
