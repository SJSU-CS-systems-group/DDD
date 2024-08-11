package net.discdd.server;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.Status;
import net.discdd.model.BundleDTO;
import net.discdd.utils.Constants;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class BundleClientToBundleServerTest extends End2EndTest {
    private static final Logger logger = Logger.getLogger(BundleClientToBundleServerTest.class.getName());
    @TempDir
    static Path clientTestRoot;
    static BundleTransmission bundleTransmission;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceStub stub;
    private static StoreADUs sendStore;
    private static StoreADUs recieveStore;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingStub;
    private static ManagedChannel channel;

    @BeforeAll
    static void setUp() throws Exception {
        var securityDir = clientTestRoot.resolve(Path.of(BundleSecurity.BUNDLE_SECURITY_DIR, BundleSecurity.SERVER_KEYS_SUBDIR));
        securityDir.toFile().mkdirs();
        Files.copy(serverIdentityKeyPath, securityDir.resolve(BundleSecurity.SERVER_IDENTITY_PUB));
        Files.copy(serverSignedPreKeyPath, securityDir.resolve(BundleSecurity.SERVER_SIGNED_PRE_PUB));
        Files.copy(serverRatchetKeyPath, securityDir.resolve(BundleSecurity.SERVER_RATCHET_PUB));

        sendStore = new StoreADUs(clientTestRoot.resolve("send"));
        recieveStore = new StoreADUs(clientTestRoot.resolve("receive"));

        bundleTransmission = new BundleTransmission(clientTestRoot, adu -> {});
        clientId = bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
    }

    @BeforeEach
    void setUpEach() {
        if (stub == null) {
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", BUNDLESERVER_GRPC_PORT).usePlaintext().build();
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
        checkReceivedFiles(Set.of());

        Assertions.assertEquals(0, sendStore.getADUs(null, TEST_APPID).count());
        Assertions.assertEquals(0, recieveStore.getADUs(null, TEST_APPID).count());


    }

    @Test
    void test3UploadBundleWithADUs() throws Exception {
        sendStore.addADU(null, TEST_APPID, "ADU1".getBytes(), 1);
        sendStore.addADU(null, TEST_APPID, "ADU2".getBytes(), 2);

        sendBundle();

        checkReceivedFiles(Set.of("1", "2"));
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

            rsp.onNext(ExchangeADUsResponse.newBuilder().setLastADUIdReceived(1).addAdus(
                    AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("SA1")).build()).build());
            rsp.onCompleted();
        });
        checkReceivedFiles(Set.of());
        checkToSendFiles(Set.of("1"));
        receiveBundle();
        checkToSendFiles(Set.of("1"));
        // TODO: waiting for trique's fix to window tracking
        /*
        Assertions.assertEquals(1, recieveStore.getADUs(null, TEST_APPID).count());

        sendBundle();
        // that send bundle should have caused the ACK to be processed and cleared the ADU to send
        */
    }


    // send the bundle the same way the client does. we should move this code into bundle transmission so we are really
    // testing the exact code that the client is using
    private static void sendBundle() throws RoutingExceptions.ClientMetaDataFileException, IOException, InvalidKeyException, GeneralSecurityException {
        BundleDTO toSend = bundleTransmission.generateBundleForTransmission();

        var bundleUploadResponseObserver = new BundleUploadResponseObserver();
        StreamObserver<BundleUploadRequest> uploadRequestStreamObserver =
                stub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .uploadBundle(bundleUploadResponseObserver);

        uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setBundleId(
                        EncryptedBundleId.newBuilder().setEncryptedId(toSend.getBundleId()).build())
                                                   .build());

        // upload file as chunk
        logger.log(INFO, "Started file transfer");
        try (FileInputStream inputStream = new FileInputStream(
                toSend.getBundle().getSource())) {
            int chunkSize = 1000 * 1000 * 4;
            byte[] bytes = new byte[chunkSize];
            int size;
            while ((size = inputStream.read(bytes)) != -1) {
                var uploadRequest = BundleUploadRequest.newBuilder().setChunk(
                        BundleChunk.newBuilder().setChunk(ByteString.copyFrom(bytes, 0, size))
                                .build()).build();
                uploadRequestStreamObserver.onNext(uploadRequest);
            }
        }
        uploadRequestStreamObserver.onCompleted();
        logger.log(INFO, "Completed file transfer");
        bundleUploadResponseObserver.waitForCompletion(Constants.GRPC_LONG_TIMEOUT_MS);
        Assertions.assertTrue(bundleUploadResponseObserver.completed, () -> bundleUploadResponseObserver.throwable.getMessage());
        Assertions.assertEquals(Status.SUCCESS, bundleUploadResponseObserver.bundleUploadResponse.getStatus());
    }

    private static void receiveBundle() throws Exception {
        var bundleRequests = bundleTransmission.getBundleSecurity().getClientWindow().getWindow(bundleTransmission.getBundleSecurity().getClientSecurity());
        var clientId = bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();

        var sender = BundleSender.newBuilder().setId(clientId).setType(BundleSenderType.CLIENT).build();

        for (String bundle : bundleRequests) {
            var downloadRequest = BundleDownloadRequest.newBuilder()
                    .setSender(sender)
                    .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(bundle).build())
                    .build();


            logger.log(INFO, "Downloading file: " + bundle);
            var responses =
                    blockingStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS).downloadBundle(downloadRequest);

            try {
                final OutputStream fileOutputStream = responses.hasNext() ?
                        // I should not have this literal here! but this change is getting too large to fix all the literal problems!
                        Files.newOutputStream(clientTestRoot.resolve("BundleTransmission/bundle-generation/to-send").resolve(bundle), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) : null;

                while (responses.hasNext()) {
                    var response = responses.next();
                    fileOutputStream.write(response.getChunk().getChunk().toByteArray());
                }
                break;
            } catch (StatusRuntimeException e) {
                logger.log(SEVERE, "Receive bundle failed " + channel, e);
            }
        }
    }
}
