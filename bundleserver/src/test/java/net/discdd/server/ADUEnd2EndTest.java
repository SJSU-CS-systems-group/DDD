package net.discdd.server;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundletransport.service.BundleMetaData;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.bundletransport.service.BundleUploadRequest;
import net.discdd.bundletransport.service.BundleUploadResponse;
import net.discdd.bundletransport.service.Status;
import net.discdd.model.Acknowledgement;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.utils.Constants;
import net.discdd.utils.DDDJarFileCreator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPublicKeyType;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.createEncodedPublicKeyBytes;

@SpringBootTest
public class ADUEnd2EndTest {
    private static final Logger logger = Logger.getLogger(ADUEnd2EndTest.class.getName());
    private static final String testAppId = "testAppId";
    @TempDir
    static Path tempRootDir;
    private static IdentityKeyPair serverIdentity;
    private static String clientId;
    private static ECKeyPair baseKeyPair;
    private static IdentityKeyPair identityKeyPair;
    private static SessionCipher clientSessionCipher;
    @Value("${grpc.server.port}")
    private int grpcPort;

    ADUEnd2EndTest(@Autowired RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter(testAppId, "localhost:6666"));
        System.out.println("**** registering " + testAppId);
    }

    @BeforeAll
    static void setup() throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
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
        ECKeyPair identityPubKeyPair = Curve.generateKeyPair();
        identityKeyPair = new IdentityKeyPair(new IdentityKey(identityPubKeyPair.getPublicKey()),
                                              identityPubKeyPair.getPrivateKey());
        baseKeyPair = Curve.generateKeyPair();
        clientId = SecurityUtils.generateID(identityKeyPair.getPublicKey().getPublicKey().serialize());

        SessionRecord sessionRecord = new SessionRecord();
        SignalProtocolAddress address = new SignalProtocolAddress(clientId, 1);
        InMemorySignalProtocolStore clientSessionStore = SecurityUtils.createInMemorySignalProtocolStore();

        AliceSignalProtocolParameters aliceSignalProtocolParameters =
                AliceSignalProtocolParameters.newBuilder().setOurBaseKey(baseKeyPair).setOurIdentityKey(identityKeyPair)
                        .setTheirOneTimePreKey(org.whispersystems.libsignal.util.guava.Optional.absent())
                        .setTheirRatchetKey(serverRatchetKey.getPublicKey())
                        .setTheirSignedPreKey(serverSignedPreKey.getPublicKey())
                        .setTheirIdentityKey(serverIdentity.getPublicKey()).create();
        RatchetingSession.initializeSession(sessionRecord.getSessionState(), aliceSignalProtocolParameters);
        clientSessionStore.storeSession(address, sessionRecord);
        clientSessionCipher = new SessionCipher(clientSessionStore, address);

    }

    private static long createBundleForAdus(List<String> adus, long currentAduId, String bundleId,
                                            Path bundleJarPath) throws IOException, NoSuchAlgorithmException,
            InvalidKeyException {
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
        // add a couple of ADUs

        for (String adu : adus) {
            innerJar.createEntry(
                    Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, testAppId, testAppId + "-" + currentAduId + ".adu"),
                    adu.getBytes());
            currentAduId++;
        }
        innerJar.close();

        // create the signed outer jar
        DDDJarFileCreator outerJar = new DDDJarFileCreator(Files.newOutputStream(bundleJarPath));
        byte[] payloadBytes = payload.toByteArray();

        // now sign the payload
        String payloadSignature = Base64.getUrlEncoder()
                .encodeToString(Curve.calculateSignature(identityKeyPair.getPrivateKey(), payloadBytes));
        outerJar.createEntry(Path.of(SIGNATURE_DIR, PAYLOAD_FILENAME + 1 + SIGNATURE_FILENAME),
                             payloadSignature.getBytes());

        // encrypt the payload

        CiphertextMessage cipherTextMessage = clientSessionCipher.encrypt(payloadBytes);
        var cipherTextBytes = cipherTextMessage.serialize();

        // store the encrypted payload
        outerJar.createEntry(Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME + 1), cipherTextBytes);
        // store the bundleId
        outerJar.createEntry(SecurityUtils.BUNDLEID_FILENAME, bundleId.getBytes());

        // store the keys
        outerJar.createEntry(SecurityUtils.CLIENT_IDENTITY_KEY,
                             createEncodedPublicKeyBytes(identityKeyPair.getPublicKey().getPublicKey()));
        outerJar.createEntry(SecurityUtils.CLIENT_BASE_KEY, createEncodedPublicKeyBytes(baseKeyPair.getPublicKey()));
        outerJar.createEntry(SecurityUtils.SERVER_IDENTITY_KEY,
                             createEncodedPublicKeyBytes(serverIdentity.getPublicKey().getPublicKey()));

        // bundle is ready
        outerJar.close();
        return currentAduId;
    }

    @SuppressWarnings("BusyWait")
    private static void checkReceivedFiles(HashSet<String> expectedFileList) throws InterruptedException {
        HashSet<String> receivedFiles;
        File aduDir = tempRootDir.resolve(Path.of("receive", clientId, testAppId)).toFile();
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
        String bundleId = BundleIDGenerator.generateBundleID(clientId, 1, BundleIDGenerator.UPSTREAM);

        var adus = List.of("ADU1", "ADU2", "ADU3");
        var currentAduId = 1L;

        Path bundleJarPath = bundleDir.resolve("outer-jar.jar");
        currentAduId = createBundleForAdus(adus, currentAduId, bundleId, bundleJarPath);
        sendBundle(bundleId, bundleJarPath);

        // check if the files are there
        HashSet<String> expectedFileList = new HashSet<>(Arrays.asList("1.adu", "2.adu", "3.adu", "metadata.json"));
        checkReceivedFiles(expectedFileList);
    }

    private void sendBundle(String bundleId, Path bundleJarPath) throws Throwable {
        var stub = BundleServiceGrpc.newStub(
                ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build());

        // carefull! this is all backwards: we pass an object to receive the response and we get an object back to send
        // requests to the server
        BundleUploadResponseStreamObserver response = new BundleUploadResponseStreamObserver();
        var request = stub.uploadBundle(response);
        request.onNext(BundleUploadRequest.newBuilder().setMetadata(
                BundleMetaData.newBuilder().setBid(bundleId + ".bundle").setTransportId("8675309").build()).build());
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
