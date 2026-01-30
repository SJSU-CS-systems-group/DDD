package net.discdd.server;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.client.bundletransmission.TransportDevice.FAKE_DEVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.whispersystems.libsignal.InvalidKeyException;

import com.google.protobuf.ByteString;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.grpc.Status;
import net.discdd.model.Bundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.utils.Constants;
import net.discdd.utils.StoreADUs;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class BundleClientToBundleServerTest extends End2EndTest {
    public static final int ADU_UPLOAD_SIZE = 10000;
    public static final int ADU_UPLOAD_COUNT = 4;
    private static final Logger logger = Logger.getLogger(BundleClientToBundleServerTest.class.getName());
    @TempDir
    static Path clientTestRoot;
    static ClientBundleTransmission bundleTransmission;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceStub stub;
    private static StoreADUs sendStore;
    private static StoreADUs recieveStore;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingStub;
    private static ManagedChannel channel;
    private static ClientPaths clientPaths;
    private static KeyPair clientKeyPair;
    private static X509Certificate clientCert;
    Random random = new Random(13 /* seed for deterministic testing */);
    @Autowired
    private SentAduDetailsRepository sentAduDetailsRepository;

    @BeforeAll
    static void setUp() throws Exception {
        clientPaths = new ClientPaths(clientTestRoot,
                                      Files.readAllBytes(serverIdentityKeyPath),
                                      Files.readAllBytes(serverSignedPreKeyPath),
                                      Files.readAllBytes(serverRatchetKeyPath));

        sendStore = new StoreADUs(clientPaths.sendADUsPath);
        recieveStore = new StoreADUs(clientPaths.receiveADUsPath);

        bundleTransmission = new ClientBundleTransmission(clientPaths, adu -> {});
        var grpcKey = bundleTransmission.getBundleSecurity().getClientGrpcSecurityKey();
        clientId = bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
        clientKeyPair = grpcKey.grpcKeyPair;
        clientCert = grpcKey.grpcCert;
    }

    // send the bundle the same way the client does. we should move this code into bundle transmission so we are really
    // testing the exact code that the client is using
    private static void sendBundle() throws RoutingExceptions.ClientMetaDataFileException, IOException,
            InvalidKeyException, GeneralSecurityException {

        Bundle toSend = bundleTransmission.generateBundleForTransmission();

        var bundleUploadResponseObserver = new BundleUploadResponseObserver();
        StreamObserver<BundleUploadRequest> uploadRequestStreamObserver = stub.withDeadlineAfter(
                                                                                                 Constants.GRPC_LONG_TIMEOUT_MS,
                                                                                                 TimeUnit.MILLISECONDS)
                .uploadBundle(bundleUploadResponseObserver);

        uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder()
                .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(toSend.getBundleId()).build())
                .build());

        // upload file as chunk
        logger.log(INFO, "Started file transfer");
        try (FileInputStream inputStream = new FileInputStream(toSend.getSource())) {
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
        uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder()
                .setSenderType(BundleSenderType.CLIENT)
                .build());
        uploadRequestStreamObserver.onCompleted();
        logger.log(INFO, "Completed file transfer");
        bundleUploadResponseObserver.waitForCompletion(Constants.GRPC_LONG_TIMEOUT_MS);
        Assertions.assertTrue(bundleUploadResponseObserver.completed,
                              () -> bundleUploadResponseObserver.throwable.getMessage());
        assertEquals(Status.SUCCESS, bundleUploadResponseObserver.bundleUploadResponse.getStatus());
    }

    private static List<String> receiveBundle() throws Exception {
        var bundleRequests = bundleTransmission.getBundleSecurity()
                .getClientWindow()
                .getWindow(bundleTransmission.getBundleSecurity().getClientSecurity());
        var clientId = bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
        var clientSecurity = bundleTransmission.getBundleSecurity().getClientSecurity();
        var bundleSecurity = bundleTransmission.getBundleSecurity();
        var receivedBundles = new ArrayList<String>();
        for (String bundle : bundleRequests) {
            PublicKeyMap publicKeyMap = PublicKeyMap.newBuilder()
                    .setClientPub(ByteString.copyFrom(clientSecurity.getClientIdentityPublicKey().serialize()))
                    .setSignedTLSPub(ByteString.copyFrom(clientSecurity.getSignedTLSPub(bundleSecurity
                            .getClientGrpcSecurityKey().grpcKeyPair.getPublic())))
                    .build();

            var downloadRequest = BundleDownloadRequest.newBuilder()
                    .setSenderType(BundleSenderType.CLIENT)
                    .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(bundle).build())
                    .setPublicKeyMap(publicKeyMap)
                    .build();

            logger.log(INFO, "Downloading file: " + bundle);
            var responses = blockingStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .downloadBundle(downloadRequest);

            try {
                Path receivedBundleLocation = clientTestRoot.resolve("BundleTransmission/bundle-generation/to-send")
                        .resolve(bundle);
                final OutputStream fileOutputStream = Files.newOutputStream(receivedBundleLocation,
                                                                            StandardOpenOption.CREATE,
                                                                            StandardOpenOption.TRUNCATE_EXISTING);

                while (responses.hasNext()) {
                    var response = responses.next();
                    fileOutputStream.write(response.getChunk().getChunk().toByteArray());
                }
                bundleTransmission.processReceivedBundle(clientId, new Bundle(receivedBundleLocation.toFile()));
                receivedBundles.add(downloadRequest.getBundleId().getEncryptedId());
            } catch (StatusRuntimeException e) {
                logger.log(SEVERE, "Receive bundle failed " + channel, e);
            }
        }
        return receivedBundles;
    }

    @BeforeEach
    void setUpEach() throws SSLException {
        if (stub == null) {
            channel = DDDNettyTLS.createGrpcChannel(clientKeyPair, clientCert, "localhost", BUNDLESERVER_GRPC_PORT);
            stub = BundleExchangeServiceGrpc.newStub(channel);
            blockingStub = BundleExchangeServiceGrpc.newBlockingStub(channel);
        }
    }

    @Test
    void test1ContextLoads() {}

    @Test
    void test2UploadFirstEmptyBundle() throws Exception {
        // this first one should be empty
        sendBundle();
        checkReceivedFiles(clientId, Set.of());

        assertEquals(0, sendStore.getADUs(null, TEST_APPID).count());
        assertEquals(0, recieveStore.getADUs(null, TEST_APPID).count());

    }

    @Test
    void test3UploadBundleWithADUs() throws Exception {
        var sentSet = new HashSet<String>();
        var sentData = new HashMap<String, byte[]>();
        for (int i = 0; i < ADU_UPLOAD_COUNT; i++) {
            int size = i * ADU_UPLOAD_SIZE + 17;
            for (int j = 0; j < 2; j++) {
                int aduId = i * 2 + 1 + j;
                byte[] aduBytes = new byte[size];
                random.nextBytes(aduBytes);
                sentSet.add(Integer.toString(aduId));
                sentData.put(Integer.toString(aduId), aduBytes);
                sendStore.addADU(null, TEST_APPID, aduBytes, aduId);
            }
            sendBundle();
            checkReceivedFiles(clientId, sentSet, sentData);
        }
        deleteReceivedFiles(clientId);
    }

    @Test
    void test4DownloadResponse() throws Exception {
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            if (req.getAdusCount() != 2) {
                logger.log(WARNING, "Received " + req.getAdusCount() + " ADUs. ignoring.");
                // we aren't interested in this one...
                rsp.onNext(ExchangeADUsResponse.newBuilder().setLastADUIdReceived(0).build());
                rsp.onCompleted();
                return;
            }

            rsp.onNext(ExchangeADUsResponse.newBuilder()
                    .setLastADUIdReceived(1)
                    .addAdus(AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("SA1")).build())
                    .build());
            rsp.onCompleted();
        });
        checkReceivedFiles(clientId, Set.of());
        checkToSendFiles(clientId, Set.of("1"));
        var receivedBundles = receiveBundle();
        checkToSendFiles(clientId, Set.of("1"));
        assertEquals(1, recieveStore.getADUs(null, TEST_APPID).count());

        for (var bundleId : receivedBundles) {
            var sentAduDetails = sentAduDetailsRepository.findByBundleId(bundleId);
            assertEquals(1, sentAduDetails.size());

            assertEquals(TEST_APPID, sentAduDetails.get(0).appId);
            assertEquals(1, sentAduDetails.get(0).aduIdRangeStart);
            assertEquals(1, sentAduDetails.get(0).aduIdRangeEnd);
        }
        sendBundle();
    }

    @Test
    void test5RecencyBlob() throws InvalidKeyException, IOException {
        var rsp = blockingStub.getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
        bundleTransmission.processRecencyBlob(FAKE_DEVICE, rsp);
        var rt = bundleTransmission.getRecentTransport(FAKE_DEVICE);
        // the blob should have been signed within the last second or so
        assertEquals((double) System.currentTimeMillis(), (double) rt.getRecencyTime(), 2000);
        var badBlob = rsp.toBuilder().setRecencyBlob(rsp.getRecencyBlob().toBuilder().setNonce(1)).build();
        // mess with the signature
        Assertions.assertThrows(IOException.class, () -> bundleTransmission.processRecencyBlob(FAKE_DEVICE, badBlob));
        // make an old blob (more than a minute old
        var oldBlob = rsp.toBuilder()
                .setRecencyBlob(rsp.getRecencyBlob().toBuilder().setBlobTimestamp(System.currentTimeMillis() - 100_000))
                .build();
        Assertions.assertThrows(IOException.class, () -> bundleTransmission.processRecencyBlob(FAKE_DEVICE, oldBlob));
    }
}
