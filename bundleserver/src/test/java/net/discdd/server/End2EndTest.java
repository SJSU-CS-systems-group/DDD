package net.discdd.server;

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPublicKeyType;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.createEncodedPublicKeyBytes;

public class End2EndTest {
    public static final String TEST_APPID = "testAppId";
    // we don't really need the atomicity part, but we need a way to pass around a mutable long
    protected final static TestAppServiceAdapter testAppServiceAdapter = new TestAppServiceAdapter();
    private static final Logger logger = Logger.getLogger(End2EndTest.class.getName());
    public static int TEST_ADAPTER_GRPC_PORT;
    protected static IdentityKeyPair serverIdentity;
    protected static String clientId;
    protected static ECKeyPair baseKeyPair;
    protected static SessionCipher clientSessionCipher;
    protected static IdentityKeyPair clientIdentity;
    @TempDir
    static Path tempRootDir;
    static Path serverIdentityKeyPath;
    private static Path serverPrivateKeyPath;
    static Path serverSignedPreKeyPath;
    private static Path serverPrivatePreKeyPath;
    static Path serverRatchetKeyPath;
    private static Path serverPrivateRatchetKeyPath;
    @Value("${grpc.server.port}")
    protected int BUNDLESERVER_GRPC_PORT;

    @BeforeAll
    static void setup() throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
        System.setProperty("serviceadapter.datacheck.interval", "5s");

        var keysDir = tempRootDir.resolve(java.nio.file.Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        Assertions.assertTrue(keysDir.toFile().mkdirs());
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        ECKeyPair serverSignedPreKey = Curve.generateKeyPair();
        ECKeyPair serverRatchetKey = Curve.generateKeyPair();
        serverIdentityKeyPath = keysDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        Files.writeString(serverIdentityKeyPath,
                          DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateKeyPath = keysDir.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY);
        Files.writeString(serverPrivateKeyPath, DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        serverSignedPreKeyPath = keysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_KEY);
        Files.writeString(serverSignedPreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivatePreKeyPath = keysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY);
        Files.writeString(serverPrivatePreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        serverRatchetKeyPath = keysDir.resolve(SecurityUtils.SERVER_RATCHET_KEY);
        Files.writeString(serverRatchetKeyPath,
                          DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateRatchetKeyPath = keysDir.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY);
        Files.writeString(serverPrivateRatchetKeyPath,
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

        var server = NettyServerBuilder.forPort(0).addService(testAppServiceAdapter).build();
        server.start();
        TEST_ADAPTER_GRPC_PORT = server.getPort();

        tempRootDir.resolve(Paths.get("send", clientId, TEST_APPID)).toFile().mkdirs();
    }

    protected static String encryptBundleID(String bundleID) throws InvalidKeyException,
            InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException,
            NoSuchAlgorithmException, InvalidKeySpecException, BadPaddingException, java.security.InvalidKeyException {
        byte[] agreement =
                Curve.calculateAgreement(serverIdentity.getPublicKey().getPublicKey(), clientIdentity.getPrivateKey());

        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);

        return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID);

    }

    static int jarCounter = 0;

    protected static Path createBundleForAdus(List<Long> adus, String clientId, int bundleCount, Path targetDir) throws IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, InvalidKeySpecException, BadPaddingException, java.security.InvalidKeyException {
        String bundleId = BundleIDGenerator.generateBundleID(clientId, bundleCount, BundleIDGenerator.UPSTREAM);
        String encryptedBundleID = encryptBundleID(bundleId);
        Path bundleJarPath = targetDir.resolve(encryptedBundleID);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DDDJarFileCreator innerJar = new DDDJarFileCreator(payload);

        // add the records to the inner jar
        Acknowledgement ackRecord = new Acknowledgement("HB");
        innerJar.createEntry("acknowledgement.txt", ackRecord.getBundleId().getBytes());
        innerJar.createEntry("routing.metadata", "{}".getBytes());

        for (var adu : adus) {
            logger.info("Adding ADU " + adu);
            innerJar.createEntry(
                    java.nio.file.Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, TEST_APPID, Long.toString(adu)),
                    String.format("ADU%d", adu).getBytes());
        }
        innerJar.close();

        // create the signed outer jar
        DDDJarFileCreator outerJar = new DDDJarFileCreator(Files.newOutputStream(bundleJarPath));
        byte[] payloadBytes = payload.toByteArray();

        // encrypt the payload
        CiphertextMessage cipherTextMessage = clientSessionCipher.encrypt(payloadBytes);
        var cipherTextBytes = cipherTextMessage.serialize();

        // store the encrypted payload
        outerJar.createEntry(java.nio.file.Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME), cipherTextBytes);
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

    protected static void checkToSendFiles(Set<String> expectedFileList) {
        HashSet<String> toSendFiles;
        File aduDir = tempRootDir.resolve(java.nio.file.Path.of("send", clientId, TEST_APPID)).toFile();
        logger.info("Checking for files in " + aduDir);
        // try for up to 10 seconds to see if the files have arrived
        for (int tries = 0;
             !(toSendFiles = new HashSet<>(listJustADUs(aduDir))).equals(expectedFileList) && tries < 20; tries++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logger.warning("Interrupted while waiting for files to be sent");
            }
        }
        Assertions.assertEquals(expectedFileList, toSendFiles);
    }

    @SuppressWarnings("BusyWait")
    protected static void checkReceivedFiles(Set<String> expectedFileList) throws InterruptedException {
        HashSet<String> receivedFiles;
        File aduDir = tempRootDir.resolve(java.nio.file.Path.of("receive", clientId, TEST_APPID)).toFile();
        logger.info("Checking for files in " + aduDir);
        // try for up to 10 seconds to see if the files have arrived
        for (int tries = 0;
             !(receivedFiles = new HashSet<>(listJustADUs(aduDir))).equals(expectedFileList) && tries < 20; tries++) {
            logger.info("Expecting " + expectedFileList + " but got " + receivedFiles);
            Thread.sleep(500);
        }

        Assertions.assertEquals(expectedFileList, receivedFiles);
    }

    private static List<String> listJustADUs(File aduDir) {
        var list = aduDir.list((d, n) -> !n.equals("metadata.json"));
        return list == null ? List.of() : Arrays.asList(list);
    }

    @Configuration
    static public class End2EndTestInitializer implements ApplicationRunner {
        final private RegisteredAppAdapterRepository registeredAppAdapterRepository;

        public End2EndTestInitializer(RegisteredAppAdapterRepository registeredAppAdapterRepository) {
            this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        }

        @Override
        public void run(ApplicationArguments args) {
            logger.info("Registering the testAppId");
            registeredAppAdapterRepository.save(
                    new RegisteredAppAdapter(TEST_APPID, "localhost:" + TEST_ADAPTER_GRPC_PORT));
        }
    }

    public static class TestAppServiceAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
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

        record AdapterRequestResponse(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> response) {}
    }

}
