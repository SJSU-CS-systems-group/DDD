package net.discdd.client.bundletransmission;

import com.google.protobuf.ByteString;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.client.applicationdatamanager.ApplicationDataManager;
import net.discdd.client.bundlerouting.ClientBundleGenerator;
import net.discdd.client.bundlerouting.ClientRouting;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.grpc.Status;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.BundleDTO;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.AckRecordUtils;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.FileUtils;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.ecc.Curve;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.utils.Constants.GRPC_LONG_TIMEOUT_MS;

public class BundleTransmission {
    private static final Logger logger = Logger.getLogger(BundleTransmission.class.getName());

    private final BundleSecurity bundleSecurity;
    private final ApplicationDataManager applicationDataManager;

    private ClientRouting clientRouting;
    private ClientPaths clientPaths;

    public BundleTransmission(ClientPaths clientPaths, Consumer<ADU> aduConsumer) throws WindowExceptions.BufferOverflow, IOException, InvalidKeyException, RoutingExceptions.ClientMetaDataFileException, NoSuchAlgorithmException {
        this.clientPaths = clientPaths;
        this.bundleSecurity = new BundleSecurity(clientPaths);
        this.applicationDataManager = new ApplicationDataManager(clientPaths, aduConsumer);
        this.clientRouting = ClientRouting.initializeInstance(clientPaths);
    }

    public ClientPaths getClientPaths() {
        return clientPaths;
    }

    public void registerBundleId(String bundleId) throws IOException, WindowExceptions.BufferOverflow,
            GeneralSecurityException, InvalidKeyException {
        try (BufferedWriter bufferedWriter = new BufferedWriter(
                new FileWriter(clientPaths.largestBundleIdReceived.toFile()))) {
            bufferedWriter.write(bundleId);
        }

        bundleSecurity.registerLargestBundleIdReceived(bundleId);
        System.out.println("[BS] Registered bundle identifier: " + bundleId);
    }

    private String getLargestBundleIdReceived() throws IOException {
        String bundleId = "";
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(clientPaths.largestBundleIdReceived.toFile()))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                bundleId = line.trim();
            }
        }
        System.out.println("[BS] Largest bundle id received so far: " + bundleId);
        return bundleId.trim();
    }

    public void processReceivedBundle(BundleSender sender, Bundle bundle) throws IOException,
            RoutingExceptions.ClientMetaDataFileException, NoSessionException, InvalidMessageException,
            DuplicateMessageException, LegacyMessageException, InvalidKeyException, GeneralSecurityException,
            WindowExceptions.BufferOverflow {
        String largestBundleIdReceived = this.getLargestBundleIdReceived();
        UncompressedBundle uncompressedBundle = BundleUtils.extractBundle(bundle, clientPaths.uncompressedPayloadStore);
        Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
        logger.log(INFO, "Updating client routing metadata for sender:  " + bundleSenderToString(sender));
        clientRouting.updateMetaData(sender.getId());

        String bundleId = payload.getBundleId();

        ClientBundleGenerator clientBundleGenerator = this.bundleSecurity.getClientBundleGenerator();
        boolean isLatestBundleId = (!largestBundleIdReceived.isEmpty() &&
                clientBundleGenerator.compareBundleIDs(bundleId, largestBundleIdReceived,
                                                       BundleIDGenerator.DOWNSTREAM) == 1);

        if (!isLatestBundleId) {
            return;
        }

        UncompressedPayload uncompressedPayload =
                BundleUtils.extractPayload(payload, uncompressedBundle.getSource().toPath());

        AckRecordUtils.writeAckRecordToFile(new Acknowledgement(bundleId), clientPaths.ackRecordPath);
        this.registerBundleId(bundleId);

        String ackedBundleId = uncompressedPayload.getAckRecord().getBundleId();

        this.applicationDataManager.processAcknowledgement(ackedBundleId);
        this.applicationDataManager.storeReceivedADUs(null, null, uncompressedPayload.getADUs());
    }

    public static String bundleSenderToString(BundleSender sender) {
        return sender.getType() + " : " + sender.getId();
    }

    private BundleDTO generateNewBundle(String bundleId) throws RoutingExceptions.ClientMetaDataFileException,
            IOException, NoSuchAlgorithmException, InvalidKeyException {
        Acknowledgement ackRecord = AckRecordUtils.readAckRecordFromFile(clientPaths.ackRecordPath);
        List<ADU> adus = this.applicationDataManager.fetchADUsToSend(clientPaths.BUNDLE_SIZE_LIMIT, null);
        var routingData = clientRouting.bundleMetaData();

        var baos = new ByteArrayOutputStream();
        var ackedEncryptedBundleId = ackRecord == null ? null : ackRecord.getBundleId();
        BundleUtils.createBundlePayloadForAdus(adus, routingData, ackedEncryptedBundleId, baos);

        ClientSecurity clientSecurity = bundleSecurity.getClientSecurity();
        Path bundleFile = clientPaths.tosendDir.resolve(bundleId);
        try (OutputStream os = Files.newOutputStream(bundleFile, StandardOpenOption.CREATE,
                                                     StandardOpenOption.TRUNCATE_EXISTING)) {
            BundleUtils.encryptPayloadAndCreateBundle(bytes -> clientSecurity.encrypt(bytes),
                                                      clientSecurity.getClientIdentityPublicKey(),
                                                      clientSecurity.getClientBaseKeyPairPublicKey(),
                                                      clientSecurity.getServerPublicKey(), bundleId, baos.toByteArray(),
                                                      os);
        }
        return new BundleDTO(bundleId, new Bundle(bundleFile.toFile()));
    }

    public BundleDTO generateBundleForTransmission() throws RoutingExceptions.ClientMetaDataFileException,
            IOException, InvalidKeyException, GeneralSecurityException {
        // find the latest sent bundle
        var sentBundles = clientPaths.tosendDir.toFile().listFiles();
        if (sentBundles != null && sentBundles.length > 0) {
            // sort in reverse order of last modified time so the newest bundle is first
            Arrays.sort(sentBundles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            File lastSentBundle = sentBundles[0];
            var lastBundleSentTimestamp = lastSentBundle.lastModified();

            // lets check to see if we have gotten new ADUs or a new ack record
            if (clientPaths.ackRecordPath.toFile().lastModified() <= lastBundleSentTimestamp &&
                    !applicationDataManager.hasNewADUs(null, lastBundleSentTimestamp)) {
                return new BundleDTO(lastSentBundle.getName(), new Bundle(lastSentBundle));
            }

            // these are all out of date, so delete them
            for (var bundle : sentBundles) {
                bundle.delete();
            }
        }
        var newBundleId = bundleSecurity.generateNewBundleId();
        return generateNewBundle(newBundleId);
    }

    /**
     * Used to track in memory recently seen transports.
     * All times are in milliseconds since epoch.
     */
    @Getter
    public static class RecentTransport {
        /**
         * from WifiP2pDevice.deviceAddress
         */
        private String deviceAddress;
        /* @param deviceName from WifiP2pDevice.deviceName */
        private String deviceName = "???";
        /* @param lastExchange time of last bundle exchange */
        private long lastExchange;
        /* @param lastSeen time of last device discovery */
        private long lastSeen;
        /* @param recencyTime time from the last recencyBlob received */
        private long recencyTime;

        public RecentTransport(String deviceAddress) {
            this.deviceAddress = deviceAddress;
        }
    }

    final private HashMap<String, RecentTransport> recentTransports = new HashMap();

    public RecentTransport[] getRecentTransports() {
        synchronized (recentTransports) {
            return recentTransports.values().toArray(new RecentTransport[0]);
        }
    }

    public RecentTransport getRecentTransport(String deviceAddress) {
        synchronized (recentTransports) {
            return recentTransports.get(deviceAddress);
        }
    }

    public void processDiscoveredPeer(String deviceAddress, String deviceName) {
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            recentTransport.deviceName = deviceName;
            recentTransport.lastSeen = System.currentTimeMillis();
        }
    }

    public void timestampExchangeWithTransport(String deviceAddress) {
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            var now = System.currentTimeMillis();
            recentTransport.lastExchange = now;
            recentTransport.lastSeen = now;
        }
    }

    public void expireNotSeenPeers(long expirationTime) {
        synchronized (recentTransports) {
            recentTransports.values().removeIf(transport -> transport.getLastSeen() < expirationTime);
        }
    }

    // returns true if the blob is more recent than previously seen
    public boolean processRecencyBlob(String deviceAddress, GetRecencyBlobResponse recencyBlobResponse) throws IOException, InvalidKeyException {
        // first make sure the data is valid
        if (recencyBlobResponse.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS) {
            throw new IOException("Recency request failed");
        }
        var recencyBlob = recencyBlobResponse.getRecencyBlob();
        // we will allow a 1 minute clock skew
        if (recencyBlob.getBlobTimestamp() > System.currentTimeMillis() + 60 * 1000) {
            throw new IOException("Recency blob timestamp is in the future");
        }
        var receivedServerPublicKey = Curve.decodePoint(recencyBlobResponse.getServerPublicKey().toByteArray(), 0);
        if (!bundleSecurity.getClientSecurity().getServerPublicKey().equals(receivedServerPublicKey)) {
            throw new IOException("Recency blob signed by unknown server");
        }
        if (!SecurityUtils.verifySignatureRaw(recencyBlob.toByteArray(), receivedServerPublicKey,
                                              recencyBlobResponse.getRecencyBlobSignature().toByteArray())) {
            throw new IOException("Recency blob signature verification failed");
        }
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            if (recencyBlob.getBlobTimestamp() > recentTransport.recencyTime) {
                recentTransport.recencyTime = recencyBlob.getBlobTimestamp();
                return true;
            }
            return false;
        }
    }

    public void notifyBundleSent(BundleDTO bundle) {
        FileUtils.recursiveDelete(bundle.getBundle().getSource().toPath());
    }

    public BundleSecurity getBundleSecurity() {
        return this.bundleSecurity;
    }

    public ClientRouting getClientRouting() {
        return clientRouting;
    }
    public enum Statuses {
        FAILED,
        EMPTY,
        COMPLETE;
        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }
    public record BundleExchangeCounts(Statuses uploadStatus, Statuses downloadStatus) {}

    private static final int INITIAL_CONNECT_RETRIES = 8;

    private boolean isServerRunning(String transportAddress, int port) {
        for (var tries = 0; tries < INITIAL_CONNECT_RETRIES; tries++) {
            try (Socket socket = new Socket(transportAddress, port)) {
                logger.log(INFO, "transport server is running");
                return true;
            } catch (IOException e) {
                logger.log(SEVERE,
                           "Try: " + tries + " | Error - message: " + e.getMessage() + ", cause: " + e.getCause());
                try {
                    Thread.sleep(500);
                } catch (Exception ex) {
                    logger.log(SEVERE, "Thread sleep error: " + ex.getMessage() + ", cause: " + ex.getCause());
                }
            }
        }
        return false;
    }

    /**
     * IT IS VERY VERY IMPORTANT THAT TRANSPORT IS THE HOSTNAME WHEN TALKING TO THE SERVER, AND AN ADDRESS
     * WHEN TALKING TO A DEVICE.
     */
    public BundleExchangeCounts doExchangeWithTransport(String deviceAddress, String deviceDeviceName,
                                                        String transportAddress, int port) {
        Statuses uploadStatus = Statuses.FAILED;
        Statuses downloadStatus = Statuses.FAILED;
        var channel =
                Grpc.newChannelBuilderForAddress(transportAddress, port, InsecureChannelCredentials.create()).build();
        var blockingStub = BundleExchangeServiceGrpc.newBlockingStub(channel);

        BundleSender transportSender = null;
        try {
            if (isServerRunning(transportAddress, port)) {
                var recencyBlobRequest = GetRecencyBlobRequest.newBuilder().setSender(
                        BundleSender.newBuilder().setId(bundleSecurity.getClientSecurity().getClientID())
                                .setType(BundleSenderType.CLIENT).build()).build();
                var blobRecencyReply = blockingStub.getRecencyBlob(recencyBlobRequest);
                var recencyBlob = blobRecencyReply.getRecencyBlob();
                if (!processRecencyBlob(deviceAddress, blobRecencyReply)) {
                    logger.log(SEVERE, "Did not process recency blob. In the future, we need to stop talking to this " +
                            "device");
                } else {
                    transportSender = recencyBlob.getSender();
                }

                timestampExchangeWithTransport(deviceAddress);
                var clientSecurity = bundleSecurity.getClientSecurity();
                var bundleRequests = bundleSecurity.getClientWindow().getWindow(clientSecurity);
                var clientId = clientSecurity.getClientID();
                var sender = BundleSender.newBuilder().setId(clientId).setType(BundleSenderType.CLIENT).build();
                var bundlesDownloaded = downloadBundles(bundleRequests, sender, blockingStub);

                try {
                    processReceivedBundle(transportSender, new Bundle(bundlesDownloaded.toFile()));
                    downloadStatus = Statuses.COMPLETE;
                } catch (Exception e) {
                    downloadStatus = Statuses.FAILED;
                    logger.log(WARNING, "Processing received bundle failed", e);
                }

                var stub = BundleExchangeServiceGrpc.newStub(channel);
                uploadStatus = uploadBundle(stub);

            }
        } catch (Exception e) {
            logger.log(WARNING, "Exchange failed", e);
        }
        try {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.log(SEVERE, "could not shutdown channel, error: " + e.getMessage() + ", cause: " + e.getCause());
        }
        return new BundleExchangeCounts(uploadStatus, downloadStatus);
    }

    private Statuses uploadBundle(BundleExchangeServiceGrpc.BundleExchangeServiceStub stub) throws RoutingExceptions.ClientMetaDataFileException, IOException, InvalidKeyException, GeneralSecurityException {
        BundleDTO toSend = generateBundleForTransmission();

        var bundleUploadResponseObserver = new BundleUploadResponseObserver();
        BundleSender clientSender = BundleSender.newBuilder().setId(bundleSecurity.getClientSecurity().getClientID())
                .setType(BundleSenderType.CLIENT).build();
        StreamObserver<BundleUploadRequest> uploadRequestStreamObserver =
                stub.withDeadlineAfter(GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .uploadBundle(bundleUploadResponseObserver);

        uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setBundleId(
                EncryptedBundleId.newBuilder().setEncryptedId(toSend.getBundleId()).build()).build());

        // upload file as chunk
        logger.log(INFO, "Started upload bundle: " + toSend.getBundleId());
        try (FileInputStream inputStream = new FileInputStream(toSend.getBundle().getSource())) {
            int chunkSize = 1000 * 1000 * 4;
            byte[] bytes = new byte[chunkSize];
            int size;
            while ((size = inputStream.read(bytes)) != -1) {
                var uploadRequest = BundleUploadRequest.newBuilder()
                        .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(bytes, 0, size)).build())
                        .build();
                uploadRequestStreamObserver.onNext(uploadRequest);
            }
        }
        uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setSender(clientSender).build());
        uploadRequestStreamObserver.onCompleted();
        bundleUploadResponseObserver.waitForCompletion(GRPC_LONG_TIMEOUT_MS);

        if(bundleUploadResponseObserver.bundleUploadResponse == null){
            logger.log(SEVERE, "Upload failed: No response received from server.");
            return Statuses.FAILED;
        }
        return bundleUploadResponseObserver.bundleUploadResponse.getStatus() == Status.SUCCESS ? Statuses.COMPLETE: Statuses.EMPTY;
    }

    private Path downloadBundles(List<String> bundleRequests, BundleSender sender,
                                 BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub stub) throws IOException {
        for (String bundle : bundleRequests) {
            var downloadRequest = BundleDownloadRequest.newBuilder()
                    .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(bundle).build()).setSender(sender)
                    .build();
            logger.log(INFO, "Downloading file: " + bundle);
            var responses =
                    stub.withDeadlineAfter(GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS).downloadBundle(downloadRequest);
            OutputStream fileOutputStream = null;

            var bundlePath = clientPaths.receiveBundlePath.resolve(bundle);

            try {
                fileOutputStream = Files.newOutputStream(bundlePath, StandardOpenOption.CREATE,
                                                         StandardOpenOption.TRUNCATE_EXISTING);

                while (responses.hasNext()) {
                    var response = responses.next();
                    fileOutputStream.write(response.getChunk().getChunk().toByteArray());
                }

                if (Files.size(bundlePath) > 0) {
                    return bundlePath;
                }
            } catch (StatusRuntimeException e) {
                logger.log(SEVERE, "Receive bundle failed " + stub.getChannel(), e);
            } finally {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.flush();
                        fileOutputStream.close();
                    } catch (IOException e) {
                        logger.log(SEVERE, "Failed to close file output stream", e);
                    }
                }
            }
        }
        return null;
    }
}

