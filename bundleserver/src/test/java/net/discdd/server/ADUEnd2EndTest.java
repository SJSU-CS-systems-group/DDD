package net.discdd.server;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundletransport.service.BundleMetaData;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.bundletransport.service.BundleUploadRequest;
import net.discdd.bundletransport.service.BundleUploadResponse;
import net.discdd.bundletransport.service.Status;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.PendingDataCheckRequest;
import net.discdd.grpc.PendingDataCheckResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.model.Acknowledgement;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.utils.Constants;
import net.discdd.utils.DDDJarFileCreator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPublicKeyType;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.createEncodedPublicKeyBytes;

@SpringBootTest(classes = { BundleServerApplication.class, ADUEnd2EndTest.ADUEnd2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class ADUEnd2EndTest {
    private static final Logger logger = Logger.getLogger(ADUEnd2EndTest.class.getName());

    public static final String TEST_APPID = "testAppId";

    @Configuration
    static public class ADUEnd2EndTestInitializer implements ApplicationRunner {
        final private RegisteredAppAdapterRepository registeredAppAdapterRepository;

        public ADUEnd2EndTestInitializer(RegisteredAppAdapterRepository registeredAppAdapterRepository) {
            this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        }

        @Override
        public void run(ApplicationArguments args) {
            logger.info("Registering the testAppId");
            registeredAppAdapterRepository.save(new RegisteredAppAdapter(TEST_APPID, "localhost:6666"));
        }
    }

    public static class TestAppServiceAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
        record AdapterRequestResponse(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> response) {}

        ConcurrentHashMap<String, String> clientsWithData = new ConcurrentHashMap<>();
        ArrayBlockingQueue<AdapterRequestResponse> incomingRequests = new ArrayBlockingQueue<>(1);

        public void handleRequest(BiConsumer<ExchangeADUsRequest, StreamObserver<ExchangeADUsResponse>> handler) throws InterruptedException {
            var handlerFuture = incomingRequests.poll(30, TimeUnit.SECONDS);
            if (handlerFuture == null) throw new IllegalStateException("No request received");
            handler.accept(handlerFuture.request, handlerFuture.response);
        }

        @Override
        public void pendingDataCheck(PendingDataCheckRequest request,
                                     StreamObserver<PendingDataCheckResponse> responseObserver) {
            PendingDataCheckResponse pendingClients =
                    PendingDataCheckResponse.newBuilder().addAllClientId(clientsWithData.keySet()).build();
            logger.info("Returning from pendingDataCheck: " + pendingClients);
            responseObserver.onNext(pendingClients);
            responseObserver.onCompleted();
        }

        @Override
        public void exchangeADUs(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> responseObserver) {
            try {
                incomingRequests.put(new AdapterRequestResponse(request, responseObserver));
            } catch (InterruptedException e) {
                logger.severe("Interrupted while waiting for request to be handled");
            }
        }
    }

    @TempDir
    static Path tempRootDir;
    private static IdentityKeyPair serverIdentity;
    private static String clientId;
    private static ECKeyPair baseKeyPair;
    private static SessionCipher clientSessionCipher;
    private static IdentityKeyPair clientIdentity;
    @Value("${grpc.server.port}")
    private int grpcPort;
    // we don't really need the atomicity part, but we need a way to pass around a mutable long
    private final static AtomicLong currentTestAppAduId = new AtomicLong(1);
    private final static TestAppServiceAdapter testAppServiceAdapter = new TestAppServiceAdapter();

    @BeforeAll
    static void setup() throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
        System.setProperty("serviceadapter.datacheck.interval", "5s");

        var keysDir = tempRootDir.resolve(Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        Assertions.assertTrue(keysDir.toFile().mkdirs());
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        ECKeyPair serverSignedPreKey = Curve.generateKeyPair();
        ECKeyPair serverRatchetKey = Curve.generateKeyPair();
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY),
                          DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY),
                          DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_KEY),
                          DDDPEMEncoder.encode(serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY),
                          DDDPEMEncoder.encode(serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_RATCHET_KEY),
                          DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY),
                          DDDPEMEncoder.encode(serverRatchetKey.getPrivateKey().serialize(), ECPrivateKeyType));

        // set up the client keys
        // create the keypairs for the client
        var clientIdentityPubKeyPair = Curve.generateKeyPair();
        clientIdentity = new IdentityKeyPair(new IdentityKey(clientIdentityPubKeyPair.getPublicKey()),
                                             clientIdentityPubKeyPair.getPrivateKey());
        baseKeyPair = Curve.generateKeyPair();
        clientId = SecurityUtils.generateID(clientIdentity.getPublicKey().getPublicKey().serialize());

        SessionRecord sessionRecord = new SessionRecord();
        SignalProtocolAddress address = new SignalProtocolAddress(clientId, 1);
        InMemorySignalProtocolStore clientSessionStore = SecurityUtils.createInMemorySignalProtocolStore();

        AliceSignalProtocolParameters aliceSignalProtocolParameters =
                AliceSignalProtocolParameters.newBuilder().setOurBaseKey(baseKeyPair).setOurIdentityKey(clientIdentity)
                        .setTheirOneTimePreKey(org.whispersystems.libsignal.util.guava.Optional.absent())
                        .setTheirRatchetKey(serverRatchetKey.getPublicKey())
                        .setTheirSignedPreKey(serverSignedPreKey.getPublicKey())
                        .setTheirIdentityKey(serverIdentity.getPublicKey()).create();
        RatchetingSession.initializeSession(sessionRecord.getSessionState(), aliceSignalProtocolParameters);
        clientSessionStore.storeSession(address, sessionRecord);
        clientSessionCipher = new SessionCipher(clientSessionStore, address);

        // start up the gRPC server

        var server = NettyServerBuilder.forPort(6666).addService(testAppServiceAdapter).build();
        server.start();

        tempRootDir.resolve(Paths.get("send", clientId, TEST_APPID)).toFile().mkdirs();
    }

    private static String encryptBundleID(String bundleID) throws InvalidKeyException,
            InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException,
            NoSuchAlgorithmException, InvalidKeySpecException, BadPaddingException, java.security.InvalidKeyException {
        byte[] agreement =
                Curve.calculateAgreement(serverIdentity.getPublicKey().getPublicKey(), clientIdentity.getPrivateKey());

        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);

        return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID);

    }

    private static Path createBundleForAdus(List<String> adus, AtomicLong currentAduId, String clientId,
                                            int bundleCount, Path targetDir) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException
            , IllegalBlockSizeException, InvalidKeySpecException, BadPaddingException,
            java.security.InvalidKeyException {
        String bundleId = BundleIDGenerator.generateBundleID(clientId, bundleCount, BundleIDGenerator.UPSTREAM);
        String encryptedBundleID = encryptBundleID(bundleId);
        Path bundleJarPath = targetDir.resolve(encryptedBundleID + ".bundle");

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DDDJarFileCreator innerJar = new DDDJarFileCreator(payload);

        // add the records to the inner jar
        Acknowledgement ackRecord = new Acknowledgement("HB");
        innerJar.createEntry("/acknowledgement.txt", ackRecord.getBundleId().getBytes());
        innerJar.createEntry("/routing.metadata", "{}".getBytes());

        // TODO: ***
        // TODO: *** there is ALOT unexpected here! the server does NOT expect and .adu suffix. the client is also
        //  putting the appId before
        // TODO: *** the ADU id, but the server ignores what is before the -.
        // TODO: ***

        for (String adu : adus) {
            innerJar.createEntry(
                    Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, TEST_APPID, TEST_APPID + "-" + currentAduId),
                    adu.getBytes());
            currentAduId.incrementAndGet();
        }
        innerJar.close();

        // create the signed outer jar
        DDDJarFileCreator outerJar = new DDDJarFileCreator(Files.newOutputStream(bundleJarPath));
        byte[] payloadBytes = payload.toByteArray();

        // now sign the payload
        String payloadSignature = Base64.getUrlEncoder()
                .encodeToString(Curve.calculateSignature(clientIdentity.getPrivateKey(), payloadBytes));
        outerJar.createEntry(Path.of(SIGNATURE_DIR, PAYLOAD_FILENAME + 1 + SIGNATURE_FILENAME),
                             payloadSignature.getBytes());

        // encrypt the payload

        CiphertextMessage cipherTextMessage = clientSessionCipher.encrypt(payloadBytes);
        var cipherTextBytes = cipherTextMessage.serialize();

        // store the encrypted payload
        outerJar.createEntry(Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME + 1), cipherTextBytes);
        // store the bundleId
        outerJar.createEntry(SecurityUtils.BUNDLEID_FILENAME, encryptedBundleID.getBytes());

        // store the keys
        outerJar.createEntry(SecurityUtils.CLIENT_IDENTITY_KEY,
                             createEncodedPublicKeyBytes(clientIdentity.getPublicKey().getPublicKey()));
        outerJar.createEntry(SecurityUtils.CLIENT_BASE_KEY, createEncodedPublicKeyBytes(baseKeyPair.getPublicKey()));
        outerJar.createEntry(SecurityUtils.SERVER_IDENTITY_KEY,
                             createEncodedPublicKeyBytes(serverIdentity.getPublicKey().getPublicKey()));

        // bundle is ready
        outerJar.close();
        return bundleJarPath;
    }

    private static void checkToSendFiles(HashSet<String> expectedFileList) {
        HashSet<String> toSendFiles;
        File aduDir = tempRootDir.resolve(Path.of("send", clientId, TEST_APPID)).toFile();
        logger.info("Checking for files in " + aduDir);
        // try for up to 10 seconds to see if the files have arrived
        for (int tries = 0;
             !(toSendFiles = new HashSet<>(Arrays.asList(requireNonNull(aduDir.list())))).equals(expectedFileList) &&
                     tries < 20; tries++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logger.warning("Interrupted while waiting for files to be sent");
            }
        }
        Assertions.assertEquals(expectedFileList, toSendFiles);
    }

    @SuppressWarnings("BusyWait")
    private static void checkReceivedFiles(HashSet<String> expectedFileList) throws InterruptedException {
        HashSet<String> receivedFiles;
        File aduDir = tempRootDir.resolve(Path.of("receive", clientId, TEST_APPID)).toFile();
        logger.info("Checking for files in " + aduDir);
        // try for up to 10 seconds to see if the files have arrived
        for (int tries = 0;
             !(receivedFiles = new HashSet<>(Arrays.asList(requireNonNull(aduDir.list())))).equals(expectedFileList) &&
                     tries < 20; tries++) {
            Thread.sleep(500);
        }

        Assertions.assertEquals(expectedFileList, receivedFiles);
    }

    @Test
    void test1ContextLoads() {}

    @Test
    void test2UploadBundle(@TempDir Path bundleDir) throws Throwable {
        var adus = List.of("ADU1", "ADU2", "ADU3");

        Path bundleJarPath = createBundleForAdus(adus, currentTestAppAduId, clientId, 1, bundleDir);
        sendBundle(bundleJarPath);

        // check if the files are there
        HashSet<String> expectedFileList = new HashSet<>(Arrays.asList("1", "2", "3", "metadata.json"));
        checkReceivedFiles(expectedFileList);

        // check the gRPC
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(0, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(3, req.getAdusCount());
            for (int i = 0; i < 3; i++) {
                Assertions.assertEquals(adus.get(i), req.getAdus(i).getData().toStringUtf8());
            }
            rsp.onNext(ExchangeADUsResponse.newBuilder().setLastADUIdReceived(3).addAdus(
                    AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("SA1")).build()).build());
            rsp.onCompleted();
        });

        // everything should disappear
        checkReceivedFiles(new HashSet<String>(List.of("metadata.json")));
        checkToSendFiles(new HashSet<>(List.of("1", "metadata.json")));
    }

    @Test
    void test3UploadMoreBundle(@TempDir Path bundleDir) throws Throwable {
        var adus = List.of("ADU3", "ADU4", "ADU5", "ADU6");
        // we are resending 3, so rewind the counter by one
        currentTestAppAduId.decrementAndGet();

        Path bundleJarPath = createBundleForAdus(adus, currentTestAppAduId, clientId, 2, bundleDir);
        sendBundle(bundleJarPath);

        // check if the files are there

        HashSet<String> expectedFileList = new HashSet<>(List.of("metadata.json"));
        for (int i = 4; i < currentTestAppAduId.get(); i++) expectedFileList.add(String.valueOf(i));
        checkReceivedFiles(expectedFileList);

        // check the gRPC
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(1, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(3, req.getAdusCount());
            for (int i = 4; i <= 6; i++) {
                Assertions.assertEquals("ADU" + i, req.getAdus(i - 4).getData().toStringUtf8());
                Assertions.assertEquals(i, req.getAdus(i - 4).getAduId());
            }
            rsp.onNext(ExchangeADUsResponse.newBuilder().setLastADUIdReceived(6).addAdus(
                    AppDataUnit.newBuilder().setAduId(2).setData(ByteString.copyFromUtf8("SA2")).build()).build());
            rsp.onCompleted();
        });
        checkToSendFiles(new HashSet<>(List.of("1", "2", "metadata.json")));
    }

    @Test
    void test4ServiceAdapterDataCheck() throws InterruptedException {
        testAppServiceAdapter.clientsWithData.put(clientId, clientId);
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(2, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(0, req.getAdusCount());
            rsp.onNext(ExchangeADUsResponse.newBuilder().setLastADUIdReceived(6).addAdus(
                    AppDataUnit.newBuilder().setAduId(3).setData(ByteString.copyFromUtf8("SA3")).build()).build());
            rsp.onCompleted();
        });
        checkToSendFiles(new HashSet<>(List.of("1", "2", "3", "metadata.json")));
    }

    private void sendBundle(Path bundleJarPath) throws Throwable {
        var stub = BundleServiceGrpc.newStub(
                ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build());

        // carefull! this is all backwards: we pass an object to receive the response and we get an object back to send
        // requests to the server
        BundleUploadResponseStreamObserver response = new BundleUploadResponseStreamObserver();
        var request = stub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS).uploadBundle(response);
        request.onNext(BundleUploadRequest.newBuilder().setMetadata(
                        BundleMetaData.newBuilder().setBid(bundleJarPath.toFile().getName()).setTransportId("8675309").build())
                               .build());
        request.onNext(BundleUploadRequest.newBuilder().setFile(net.discdd.bundletransport.service.File.newBuilder()
                                                                        .setContent(ByteString.copyFrom(
                                                                                Files.readAllBytes(bundleJarPath)))
                                                                        .build()).build());
        request.onCompleted();

        // let's see if it worked...
        Assertions.assertTrue(response.waitForCompletion(Duration.of(30, ChronoUnit.SECONDS)),
                              "Timed out waiting for bundle upload RPC");
        if (response.throwable != null) throw response.throwable;
        if (response.response == null) throw new IllegalStateException("No response received");
        var bundleUploadResponse = response.response;
        Assertions.assertEquals(Status.SUCCESS, bundleUploadResponse.getStatus());

        // TODO: it doesn't look like the bundle ID is being returned. we should remove from the proto
        // Assertions.assertEquals(bundleJarPath.toFile().getName(), bundleUploadResponse.getBid());
    }

    private static class BundleUploadResponseStreamObserver implements StreamObserver<BundleUploadResponse> {
        public boolean completed = false;
        public BundleUploadResponse response;
        public Throwable throwable;

        @Override
        public void onNext(BundleUploadResponse bundleUploadResponse) {
            this.response = bundleUploadResponse;
        }

        @Override
        public void onError(Throwable throwable) {
            this.throwable = throwable;
        }

        synchronized public boolean waitForCompletion(Duration waitTime) {
            if (!completed) {
                try {
                    wait(waitTime.toMillis());
                } catch (InterruptedException e) {
                    logger.warning("Interrupted while waiting for completion");
                }
            }
            return completed;
        }

        @Override
        synchronized public void onCompleted() {
            this.completed = true;
            notifyAll();
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }
}
