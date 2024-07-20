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
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
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
    private static ECKeyPair serverSignedPreKey;
    private static ECKeyPair serverRatchetKey;
    @Value("${grpc.server.port}")
    private int grpcPort;

    ADUEnd2EndTest(@Autowired RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter(testAppId, "localhost:6666"));
    }

    @BeforeAll
    static void setup() throws IOException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
        var keysDir = tempRootDir.resolve(Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        Assertions.assertTrue(keysDir.toFile().mkdirs());
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        serverSignedPreKey = Curve.generateKeyPair();
        serverRatchetKey = Curve.generateKeyPair();
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
    }

    @Test
    void test1ContextLoads() {}

    @Test
    @SuppressWarnings("BusyWait")
    void test2UploadBundle(@TempDir Path bundleDir) throws Throwable {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DDDJarFileCreator innerJar = new DDDJarFileCreator(payload);

        // create the keypairs for the client
        ECKeyPair identityPubKeyPair = Curve.generateKeyPair();
        IdentityKeyPair identityKeyPair = new IdentityKeyPair(new IdentityKey(identityPubKeyPair.getPublicKey()),
                                                              identityPubKeyPair.getPrivateKey());
        ECKeyPair baseKeyPair = Curve.generateKeyPair();
        String clientId = SecurityUtils.generateID(identityKeyPair.getPublicKey().getPublicKey().serialize());

        String bundleId = BundleIDGenerator.generateBundleID(clientId, 1, BundleIDGenerator.UPSTREAM);

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
        innerJar.createEntry(Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, testAppId, testAppId + "-1"), "ADU1".getBytes());
        innerJar.createEntry(Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, testAppId, testAppId + "-2"), "ADU2".getBytes());
        innerJar.createEntry(Path.of(Constants.BUNDLE_ADU_DIRECTORY_NAME, testAppId, testAppId + "-3"), "ADU3".getBytes());
        innerJar.close();

        // create the signed outer jar
        Path bundleJarPath = bundleDir.resolve("outer-jar.jar");
        DDDJarFileCreator outerJar = new DDDJarFileCreator(Files.newOutputStream(bundleJarPath));
        byte[] payloadBytes = payload.toByteArray();

        // now sign the payload
        String payloadSignature = Base64.getUrlEncoder()
                .encodeToString(Curve.calculateSignature(identityKeyPair.getPrivateKey(), payloadBytes));
        outerJar.createEntry(Path.of(SIGNATURE_DIR, PAYLOAD_FILENAME + 1 + SIGNATURE_FILENAME), payloadSignature.getBytes());

        // encrypt the payload
        SessionRecord sessionRecord = new SessionRecord();
        SignalProtocolAddress address = new SignalProtocolAddress(clientId, 1);
        var sessionStore = SecurityUtils.createInMemorySignalProtocolStore();

        AliceSignalProtocolParameters parameters =
                AliceSignalProtocolParameters.newBuilder().setOurBaseKey(baseKeyPair).setOurIdentityKey(identityKeyPair)
                        .setTheirOneTimePreKey(org.whispersystems.libsignal.util.guava.Optional.absent())
                        .setTheirRatchetKey(serverRatchetKey.getPublicKey())
                        .setTheirSignedPreKey(serverSignedPreKey.getPublicKey())
                        .setTheirIdentityKey(serverIdentity.getPublicKey()).create();
        RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters);
        sessionStore.storeSession(address, sessionRecord);
        SessionCipher sessionCipher = new SessionCipher(sessionStore, address);

        CiphertextMessage cipherTextMessage = sessionCipher.encrypt(payloadBytes);
        var cipherTextBytes = cipherTextMessage.serialize();

        // store the encrypted payload
        outerJar.createEntry(Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME + 1), cipherTextBytes);
        // store the bundleId
        outerJar.createEntry(SecurityUtils.BUNDLEID_FILENAME, bundleId.getBytes());

        // store the keys
        outerJar.createEntry(SecurityUtils.CLIENT_IDENTITY_KEY, createEncodedPublicKeyBytes(identityKeyPair.getPublicKey().getPublicKey()));
        outerJar.createEntry(SecurityUtils.CLIENT_BASE_KEY, createEncodedPublicKeyBytes(baseKeyPair.getPublicKey()));
        outerJar.createEntry(SecurityUtils.SERVER_IDENTITY_KEY, createEncodedPublicKeyBytes(serverIdentity.getPublicKey().getPublicKey()));

        // bundle is ready
        outerJar.close();

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

        // check if the files are there
        HashSet<String> receivedFiles;
        HashSet<String> expectedFileList = new HashSet<>(Arrays.asList("1.adu", "2.adu", "3.adu", "metadata.json"));
        File aduDir = tempRootDir.resolve(Path.of("receive", clientId, testAppId)).toFile();
        // try for up to 10 seconds to see if the files have arrived
        for (int tries = 0;
                !(receivedFiles = new HashSet<>(Arrays.asList(requireNonNull(aduDir.list())))).equals(expectedFileList)
                        && tries < 20;
                tries++) {
            Thread.sleep(500);
        }

        Assertions.assertEquals(expectedFileList, receivedFiles);
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
