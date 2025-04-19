package net.discdd.server;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.Status;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class ADUEnd2EndTest extends End2EndTest {
    private static final Logger logger = Logger.getLogger(ADUEnd2EndTest.class.getName());

    private static KeyPair clientKeyPair;
    private static X509Certificate clientCert;

    static {
        try {
            clientKeyPair = DDDTLSUtil.generateKeyPair();
            clientCert = DDDTLSUtil.getSelfSignedCertificate(clientKeyPair,
                                                             DDDTLSUtil.publicKeyToName(clientKeyPair.getPublic()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void test1ContextLoads() {}

    @Test
    void test2UploadBundle(@TempDir Path bundleDir) throws Throwable {
        var adus = List.of(1L, 2L, 3L);
        Path bundleJarPath = createBundleForAdus(adus, clientId, 1, bundleDir);
        sendBundle(bundleJarPath);

        // check if the files are there
        HashSet<String> expectedFileList = new HashSet<>(Arrays.asList("1", "2", "3"));
        checkReceivedFiles(clientId, expectedFileList);

        // check the gRPC
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(0, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(3, req.getAdusCount());
            for (int i = 0; i < 3; i++) {
                // we are checking the data of the ADUs
                Assertions.assertEquals("ADU" + adus.get(i), req.getAdus(i).getData().toStringUtf8());
            }
            rsp.onNext(ExchangeADUsResponse.newBuilder()
                               .setLastADUIdReceived(3)
                               .addAdus(AppDataUnit.newBuilder()
                                                .setAduId(1)
                                                .setData(ByteString.copyFromUtf8("SA1"))
                                                .build())
                               .build());
            rsp.onCompleted();
        });

        // everything should disappear
        checkReceivedFiles(clientId, new HashSet<String>(List.of()));
        checkToSendFiles(clientId, new HashSet<>(List.of("1")));
    }

    @Test
    void test3UploadMoreBundle(@TempDir Path bundleDir) throws Throwable {
        var adus = List.of(3L, 4L, 5L, 6L);

        Path bundleJarPath = createBundleForAdus(adus, clientId, 2, bundleDir);
        logger.info("Sending test3 bundle");
        sendBundle(bundleJarPath);

        // check if the files are there

        HashSet<String> expectedFileList = new HashSet<>(List.of());
        for (int i = 4; i <= adus.get(adus.size() - 1); i++) expectedFileList.add(String.valueOf(i));
        checkReceivedFiles(clientId, expectedFileList);

        // check the gRPC
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(1, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(3, req.getAdusCount());
            for (int i = 4; i <= 6; i++) {
                Assertions.assertEquals("ADU" + i, req.getAdus(i - 4).getData().toStringUtf8());
                Assertions.assertEquals(i, req.getAdus(i - 4).getAduId());
            }
            rsp.onNext(ExchangeADUsResponse.newBuilder()
                               .setLastADUIdReceived(6)
                               .addAdus(AppDataUnit.newBuilder()
                                                .setAduId(2)
                                                .setData(ByteString.copyFromUtf8("SA2"))
                                                .build())
                               .build());
            rsp.onCompleted();
        });
        checkToSendFiles(clientId, new HashSet<>(List.of("1", "2")));
    }

    @Test
    void test4ServiceAdapterDataCheck() throws InterruptedException {
        testAppServiceAdapter.clientsWithData.put(clientId, clientId);
        testAppServiceAdapter.handleRequest((req, rsp) -> {
            Assertions.assertEquals(2, req.getLastADUIdReceived());
            Assertions.assertEquals(clientId, req.getClientId());
            Assertions.assertEquals(0, req.getAdusCount());
            rsp.onNext(ExchangeADUsResponse.newBuilder()
                               .setLastADUIdReceived(6)
                               .addAdus(AppDataUnit.newBuilder()
                                                .setAduId(3)
                                                .setData(ByteString.copyFromUtf8("SA3"))
                                                .build())
                               .build());
            rsp.onCompleted();
        });
        checkToSendFiles(clientId, new HashSet<>(List.of("1", "2", "3")));
    }

    private void sendBundle(Path bundleJarPath) throws Throwable {
        var channel = DDDNettyTLS.createGrpcChannel(clientKeyPair, clientCert, "localhost", BUNDLESERVER_GRPC_PORT);

        var stub = BundleExchangeServiceGrpc.newStub(channel);

        // carefull! this is all backwards: we pass an object to receive the response and we get an object back to send
        // requests to the server
        BundleUploadResponseStreamObserver response = new BundleUploadResponseStreamObserver();
        var request = stub.uploadBundle(response);
        var allBytes = Files.readAllBytes(bundleJarPath);
        var firstByteString = ByteString.copyFrom(allBytes, 0, allBytes.length / 2);
        var secondByteString =
                ByteString.copyFrom(allBytes, allBytes.length / 2, allBytes.length - allBytes.length / 2);
        request.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
        request.onNext(BundleUploadRequest.newBuilder()
                               .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId("8675309").build())
                               .build());
        request.onNext(BundleUploadRequest.newBuilder()
                               .setChunk(BundleChunk.newBuilder().setChunk(firstByteString).build())
                               .build());
        request.onNext(BundleUploadRequest.newBuilder()
                               .setChunk(BundleChunk.newBuilder().setChunk(secondByteString).build())
                               .build());
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
