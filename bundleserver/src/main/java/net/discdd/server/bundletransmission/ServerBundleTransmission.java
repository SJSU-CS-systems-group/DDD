package net.discdd.server.bundletransmission;

import com.google.protobuf.ByteString;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.Bundle;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundlerouting.BundleRouting;
import net.discdd.server.bundlerouting.ServerWindowService;
import net.discdd.server.bundlesecurity.ServerBundleSecurity;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.entity.ClientBundleCounters;
import net.discdd.server.repository.entity.ReceivedBundle;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
import net.discdd.utils.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.bundlesecurity.SecurityUtils.generateID;
import static net.discdd.grpc.BundleSenderType.CLIENT;

@EnableAsync
@Service
public class ServerBundleTransmission {

    private static final Logger logger = Logger.getLogger(ServerBundleTransmission.class.getName());
    private static final ReentrantLock[] receiveLocks = new ReentrantLock[64];
    static {
        Arrays.setAll(receiveLocks, ignored -> new ReentrantLock());
    }
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    public static final int WINDOW_LENGTH = 3;
    private final BundleServerConfig config;
    private final ServerBundleSecurity bundleSecurity;
    private final ServerApplicationDataManager applicationDataManager;
    private final BundleRouting bundleRouting;
    private final ServerWindowService serverWindowService;
    private final ServerSecurity serverSecurity;
    private final ReceivedBundleRepository receivedBundleRepository;
    @Value("${bundle-server.bundle-store-shared}")
    private String bundleStoreShared;
    SecureRandom secureRandom = new SecureRandom();

    public ServerBundleTransmission(ServerBundleSecurity bundleSecurity,
                                    ServerApplicationDataManager applicationDataManager,
                                    BundleRouting bundleRouting,
                                    BundleServerConfig config,
                                    ServerWindowService serverWindowService,
                                    ServerSecurity serverSecurity,
                                    ReceivedBundleRepository receivedBundleRepository) {
        this.config = config;
        this.receivedBundleRepository = receivedBundleRepository;
        this.bundleSecurity = bundleSecurity;
        this.applicationDataManager = applicationDataManager;
        this.bundleRouting = bundleRouting;
        this.serverWindowService = serverWindowService;
        this.serverSecurity = serverSecurity;
    }

    public static String bundleSenderToString(BundleSenderType senderType, String senderId) {
        return senderType + " : " + senderId;
    }

    private static Random random = new Random();

    private String nextRandomString() {
        return Long.toHexString(random.nextLong());
    }

    @Async
    @Transactional
    public void processReceivedBundle(BundleSenderType senderType, String senderId, Bundle bundle) throws Exception {
        logger.log(INFO,
                   "Processing received bundle: " + bundle.getSource().getName() + " from " +
                           bundleSenderToString(senderType, senderId));
        if (!bundle.getSource().exists() || bundle.getSource().length() == 0) {
            return;
        }

        Path bundleRecvProcDir =
                this.config.getBundleTransmission().getReceivedProcessingDirectory().resolve(nextRandomString());
        try {
            Files.createDirectories(bundleRecvProcDir);

            UncompressedBundle uncompressedBundle = BundleUtils.extractBundle(bundle, bundleRecvProcDir);
            String serverIdReceived =
                    generateID(uncompressedBundle.getSource().toPath().resolve(SecurityUtils.SERVER_IDENTITY_KEY));
            if (!bundleSecurity.bundleServerIdMatchesCurrentServer(serverIdReceived)) {
                logger.log(WARNING,
                           "Received bundle's serverIdentity didn't match with current server, " +
                                   "ignoring bundle with bundleId: " + uncompressedBundle.getBundleId());
                return;
            }

            String clientIdBase64 = DDDPEMEncoder.decodeEncryptedPublicKeyfromFile(serverSecurity.getSigningKey(),
                                                                                   uncompressedBundle.getSource()
                                                                                           .toPath()
                                                                                           .resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
            String clientId = generateID(clientIdBase64);
            var receivedBundleCounter =
                    this.bundleSecurity.getCounterFromBundlePath(uncompressedBundle.getSource().toPath(),
                                                                 BundleIDGenerator.UPSTREAM);
            Path stagedPayload = stagedPayloadPath(clientId, uncompressedBundle.getBundleId(), bundle.getSource().toPath());
            ReentrantLock receiveLock = receiveLocks[Math.floorMod(clientId.hashCode(), receiveLocks.length)];
            receiveLock.lock();
            boolean unlockWithTransaction = false;
            try {
                // A client's Signal session and receive counter are shared by all of its bundles. Keep
                // the lock through transaction completion so the next bundle sees the committed counter.
                if (TransactionSynchronizationManager.isActualTransactionActive() &&
                        TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            receiveLock.unlock();
                        }
                    });
                    unlockWithTransaction = true;
                }
                var counters = this.applicationDataManager.getBundleCountersForClient(clientId);
                if (receivedBundleCounter <= counters.lastReceivedBundleCounter) {
                    // The counter advances only after every ADU was stored.
                    recordReceivedBundle(uncompressedBundle, clientId, receivedBundleCounter, senderId);
                    deleteStagedPayloadAfterCommit(stagedPayload);
                    logger.log(WARNING,
                               "[BundleTransmission] Skipping bundle " + bundle.getSource().getName() +
                                       " already received");
                    return;
                }

                Payload payload = decryptOrResume(uncompressedBundle, stagedPayload);

                UncompressedPayload uncompressedPayload =
                        BundleUtils.extractPayload(payload, uncompressedBundle.getSource().toPath());
                logger.log(INFO,
                           "[CrashReports] extracted payload to: " + uncompressedPayload.getSource().getAbsolutePath());
                Path crashReportSrcDir =
                        uncompressedPayload.getSource().toPath().resolve(Constants.BUNDLE_CRASH_REPORT_DIRECTORY_NAME);
                logger.log(INFO,
                           "[CrashReports] looking for crash reports in: " + crashReportSrcDir + " exists=" +
                                   Files.isDirectory(crashReportSrcDir));
                if (Files.isDirectory(crashReportSrcDir)) {
                    Path destDir = Path.of(bundleStoreShared, "crashReports", "client");
                    logger.log(INFO, "[CrashReports] writing to destDir: " + destDir);
                    try {
                        Files.createDirectories(destDir);
                        long timestamp = System.currentTimeMillis();
                        try (var entries = Files.list(crashReportSrcDir).filter(Files::isRegularFile)) {
                            var reports = entries.sorted().collect(Collectors.toList());
                            logger.log(INFO,
                                       "[CrashReports] found " + reports.size() + " report(s) for client " + clientId);
                            for (int i = 0; i < reports.size(); i++) {
                                Path dest = destDir.resolve(clientId + "_" + timestamp + "_" + (i + 1));
                                logger.log(INFO, "[CrashReports] copying " + reports.get(i) + " -> " + dest);
                                Files.copy(reports.get(i), dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(WARNING, "[CrashReports] failed to extract crash reports for client " + clientId, e);
                    }
                }
                if (!"HB".equals(uncompressedPayload.getAckRecord().getBundleId())) {
                    this.serverWindowService.processACK(clientId, uncompressedPayload.getAckRecord().getBundleId());
                }

                try {
                    this.bundleRouting.processClientMetaData(uncompressedPayload.getSource().toPath(), senderId, clientId);
                } catch (ClientMetaDataFileException | SQLException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                this.applicationDataManager.processAcknowledgement(clientId,
                                                                   uncompressedPayload.getAckRecord().getBundleId());
                this.applicationDataManager.storeReceivedADUs(clientId,
                                                              uncompressedPayload.getBundleId(),
                                                              receivedBundleCounter,
                                                              uncompressedPayload.getADUs());
                // only now: the payload decrypted and its ack and ADUs are stored (ack-only bundles included)
                recordReceivedBundle(uncompressedBundle, clientId, receivedBundleCounter, senderId);
                deleteStagedPayloadAfterCommit(stagedPayload);
            } finally {
                if (!unlockWithTransaction) receiveLock.unlock();
            }
        } finally {
            FileUtils.recursiveDelete(bundleRecvProcDir);
        }
    }

    /**
     * Signal consumes a message when it decrypts it, before the receive store can write the ADUs. Keep the
     * authenticated plaintext payload across a store failure so the same encrypted bundle can be retried.
     */
    private Payload decryptOrResume(UncompressedBundle bundle, Path stagedPayload) throws Exception {
        Path stageDir = stagedPayload.getParent();
        Path decrypted = stageDir.resolve(bundle.getBundleId() + SecurityUtils.DECRYPTED_FILE_EXT);
        Path marker = stageDir.resolve("authenticated.sha256");
        if (Files.isRegularFile(marker)) {
            String expected = Files.readString(marker);
            if (!expected.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid authenticated receive payload marker: " + marker);
            }
            if (expected.equals(sha256(stagedPayload))) return new Payload(bundle.getBundleId(), stagedPayload.toFile());
            if (expected.equals(sha256(decrypted))) {
                return new Payload(bundle.getBundleId(), promoteOrKeep(decrypted, stagedPayload).toFile());
            }
            throw new IOException("Authenticated receive payload is missing or changed: " + stageDir);
        }

        Files.createDirectories(stageDir);
        // A decrypt interrupted before its authentication marker may have left partial plaintext.
        Files.deleteIfExists(decrypted);
        Path output = serverSecurity.decrypt(bundle.getSource().toPath(), stageDir);
        Files.writeString(marker, sha256(output));
        return new Payload(bundle.getBundleId(), promoteOrKeep(output, stagedPayload).toFile());
    }

    private Path promoteOrKeep(Path authenticatedOutput, Path stagedPayload) {
        try {
            return Files.move(authenticatedOutput, stagedPayload, StandardCopyOption.ATOMIC_MOVE,
                              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                return Files.move(authenticatedOutput, stagedPayload, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                logger.log(WARNING, "Could not promote authenticated receive payload; using its original path",
                           fallbackFailure);
                return authenticatedOutput;
            }
        } catch (IOException e) {
            logger.log(WARNING, "Could not promote authenticated receive payload; using its original path", e);
            return authenticatedOutput;
        }
    }

    private static String sha256(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path stagedPayloadPath(String clientId, String bundleId, Path uploadedJar) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((clientId + "\0" + bundleId + "\0").getBytes(UTF_8));
        try (var input = new DigestInputStream(Files.newInputStream(uploadedJar), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return config.getBundleTransmission().getReceivedProcessingDirectory()
                .resolve("retry-payloads")
                .resolve(HexFormat.of().formatHex(digest.digest()))
                .resolve("payload.jar");
    }

    private void deleteStagedPayload(Path stagedPayload) {
        try {
            if (!Files.isDirectory(stagedPayload.getParent())) return;
            try (var entries = Files.list(stagedPayload.getParent())) {
                for (Path entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(stagedPayload.getParent());
        } catch (IOException e) {
            logger.log(WARNING, "Could not remove received payload staging file " + stagedPayload, e);
        }
    }

    private void deleteStagedPayloadAfterCommit(Path stagedPayload) {
        if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteStagedPayload(stagedPayload);
                }
            });
        } else {
            deleteStagedPayload(stagedPayload);
        }
    }

    /**
     * Remember that this upstream bundle reached the server, keyed by the jar's plaintext {@code bundle.id} (the same
     * string a dropbox uses as the file name). The dropbox retention planner turns this into RECEIVED_BY_SERVER and
     * SUPERSEDED decisions, so a row must mean "processed", never just "arrived": it is written only after the
     * payload decrypted and its ack and ADUs were stored, or for a duplicate whose counter the server has already
     * processed. A bundle that fails part-way leaves no row ({@code processBundleFile} swallows the exception, so
     * the RPC's transaction still commits whatever ran before). Ack-only bundles advance the same counter after
     * their acknowledgement is processed. A failure to record is logged and does not fail the bundle; the dropbox
     * then keeps its copy a while longer.
     */
    private void recordReceivedBundle(UncompressedBundle uncompressedBundle,
                                      String clientId,
                                      long bundleCounter,
                                      String uploaderId) {
        try {
            String bundleId = uncompressedBundle.getBundleId();
            if (receivedBundleRepository.existsById(bundleId)) return;
            String fingerprint = null;
            Path baseKeyPath = uncompressedBundle.getSource().toPath().resolve(SecurityUtils.CLIENT_BASE_KEY);
            if (Files.isRegularFile(baseKeyPath)) {
                fingerprint = BundleJarInfo.clientKeyFingerprintFromPem(Files.readString(baseKeyPath));
            }
            receivedBundleRepository.save(new ReceivedBundle(bundleId,
                                                             clientId,
                                                             bundleCounter,
                                                             fingerprint,
                                                             System.currentTimeMillis(),
                                                             uploaderId));
        } catch (Exception e) {
            logger.log(WARNING, "Could not record received bundle for client " + clientId, e);
        }
    }

    public void processBundleFile(File bundleFile, BundleSenderType senderType, String senderId) {
        Bundle bundle = new Bundle(bundleFile);
        try {
            this.processReceivedBundle(senderType, senderId, bundle);
        } catch (Exception e) {
            logger.log(SEVERE,
                       "[BundleTransmission] Failed to process received bundle from: " +
                               bundleSenderToString(senderType, senderId),
                       e);
        } finally {
            FileUtils.recursiveDelete(bundle.getSource().toPath());
        }
    }

    public String generateBundleId(String clientId) {
        if (this.serverWindowService.generateNewBundleCounter(clientId) <= 0) {
            logger.log(INFO, "Server's window is full for the client " + clientId);
        }
        return this.applicationDataManager.getBundleCountersForClient(clientId).lastSentBundleId;
    }

    public String generateBundleForClient(String clientId) throws InvalidClientIDException, GeneralSecurityException,
            InvalidKeyException, IOException {
        logger.log(INFO, "[BundleTransmission] Processing bundle generation request for client " + clientId);
        // lets in-process apps (dropbox retention plans) refresh their queued ADUs from the latest server facts
        applicationDataManager.beforeBundleGeneration(clientId);

        ClientBundleCounters bundleCountersForClient = this.applicationDataManager.getBundleCountersForClient(clientId);
        if (this.serverWindowService.isWindowFull(clientId)) {
            logger.log(INFO,
                       "Server's window is full for the client " + clientId + " returning last sent bundle ID " +
                               bundleCountersForClient.lastSentBundleId);
            return bundleCountersForClient.lastSentBundleId;
        }

        var counts = bundleCountersForClient;
        if (counts.lastSentBundleCounter > 0 && !applicationDataManager.newDataToSend(counts.lastSentBundleId) &&
                !applicationDataManager.newAckNeeded(counts.lastSentBundleId)) {
            // Nothing new to send, so lets send the last bundle again.
            return counts.lastSentBundleId;
        }

        var bundleCounter = counts.lastSentBundleCounter + 1;
        var encryptedBundleId =
                serverSecurity.createEncryptedBundleId(clientId, bundleCounter, BundleIDGenerator.DOWNSTREAM);
        long ackedRecievedBundle = counts.lastReceivedBundleCounter;
        applicationDataManager.registerNewBundleId(clientId, encryptedBundleId, bundleCounter, ackedRecievedBundle);

        var adus = applicationDataManager.fetchADUsToSend(encryptedBundleId, bundleCounter, 0, clientId);
        PipedInputStream pipedInputStream = new PipedInputStream();
        Future<?> future = BundleUtils.runFuture(executorService,
                                                 counts.lastReceivedBundleId,
                                                 null,
                                                 adus,
                                                 null,
                                                 pipedInputStream,
                                                 applicationDataManager.getRegisteredAppIds(clientId));
        Files.createDirectories(getClientSendDirectory(clientId));
        try {
            var bundleOutputStream = Files.newOutputStream(getPathForBundleToSend(clientId, encryptedBundleId),
                                                           StandardOpenOption.CREATE,
                                                           StandardOpenOption.TRUNCATE_EXISTING);
            BundleUtils.encryptPayloadAndCreateBundle((inputStream, outputStream) -> serverSecurity.encrypt(clientId,
                                                                                                            inputStream,
                                                                                                            outputStream),
                                                      serverSecurity.getClientIdentityPublicKey(clientId),
                                                      serverSecurity.getClientBaseKey(clientId),
                                                      serverSecurity.getIdentityPublicKey().getPublicKey(),
                                                      encryptedBundleId,
                                                      pipedInputStream,
                                                      bundleOutputStream);

        } catch (InvalidMessageException e) {
            throw new GeneralSecurityException(e);
        } finally {
            future.cancel(true);
        }
        cleanupOldBundles(clientId, encryptedBundleId);
        logger.log(INFO,
                   "Bundle generated for client " + clientId + " with ID: " + encryptedBundleId + " in " +
                           getPathForBundleToSend(clientId, encryptedBundleId));
        return encryptedBundleId;
    }

    public GetRecencyBlobResponse getRecencyBlob(String senderId) throws InvalidKeyException {
        var blob = RecencyBlob.newBuilder()
                .setVersion(0)
                .setNonce(secureRandom.nextInt())
                .setBlobTimestamp(System.currentTimeMillis())
                .setSenderId(senderId)
                .build();
        byte[] signature = this.bundleSecurity.signRecencyBlob(blob);
        byte[] publicKeyBytes = this.bundleSecurity.getIdentityPublicKey();
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(blob)
                .setRecencyBlobSignature(ByteString.copyFrom(signature))
                .setServerPublicKey(ByteString.copyFrom(publicKeyBytes))
                .build();
    }

    public Path getClientSendDirectory(String clientId) {
        return config.getBundleTransmission().getToSendDirectory().resolve(clientId);
    }

    public Path getPathForBundleToSend(String clientId, String encryptedBundleId) {
        return getClientSendDirectory(clientId).resolve(encryptedBundleId);
    }

    public Path getPathForBundleToSend(String encryptedBundleId) {
        var clientId = applicationDataManager.getClientIdForBundle(encryptedBundleId);
        if (clientId != null) {
            var perClientPath = getPathForBundleToSend(clientId, encryptedBundleId);
            if (Files.exists(perClientPath)) {
                return perClientPath;
            }
        }
        // Fallback: flat directory (backward compat for pre-existing bundles)
        return config.getBundleTransmission().getToSendDirectory().resolve(encryptedBundleId);
    }

    private void cleanupOldBundles(String clientId, String currentBundleId) {
        var clientDir = getClientSendDirectory(clientId);
        var files = clientDir.toFile().listFiles();
        if (files != null) {
            for (var f : files) {
                if (!f.getName().equals(currentBundleId)) {
                    logger.log(INFO, "Deleting old bundle " + f.getName() + " for client " + clientId);
                    f.delete();
                }
            }
        }
    }

    public Path getPathForBundleToReceive(String randomBundleId) {
        return getPathForBundleReceiveDirectory().resolve(randomBundleId);
    }

    private Path getPathForBundleReceiveDirectory() {
        return config.getBundleTransmission().getBundleReceivedLocation();
    }

    public BundlesToExchange inventoryBundlesForTransmission(BundleSenderType senderType,
                                                             String senderId,
                                                             Set<String> bundleIdsPresent) {
        List<String> clientIds = CLIENT == senderType ?
                                 Collections.singletonList(senderId) :
                                 this.bundleRouting.getClientsForTransportId(senderId);

        logger.log(INFO,
                   "[BundleTransmission] Found " + clientIds.size() + " reachable from the sender: " +
                           bundleSenderToString(senderType, senderId));
        Set<String> deletionSet = new HashSet<>(bundleIdsPresent);
        List<String> bundlesToSend = new ArrayList<>();

        for (String clientId : clientIds) {
            try {
                var clientBundle = this.generateBundleForClient(clientId);
                bundlesToSend.add(clientBundle);
            } catch (InvalidClientIDException | GeneralSecurityException | InvalidKeyException | IOException e) {
                logger.log(SEVERE, "Failed to generate bundle for client " + clientId, e);
            }
        }
        bundlesToSend.forEach(deletionSet::remove);

        // the bundleIdsPresent.stream().toList() doesn't actually contain the bundlesToUpload
        // in this method it's just as a placeholder, because we don't have the bundlesToUpload here
        // look at net.discdd.server.service.BundleServerServiceImpl.bundleInventory to see how the return object is
        // used
        return new BundlesToExchange(bundlesToSend, bundleIdsPresent.stream().toList(), new ArrayList<>(deletionSet));
    }

    public record BundlesToExchange(List<String> bundlesToDownload,
                                    List<String> bundlesToUpload,
                                    List<String> bundlesToDelete) {}
}
