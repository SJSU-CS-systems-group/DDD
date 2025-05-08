package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@SpringBootTest
class EchoApplicationTests {
    private static final Logger logger = Logger.getLogger(EchoApplicationTests.class.getName());

    @Value("${ssl-grpc.server.port}")
    private int port;
    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("adapter-server.root-dir", () -> tempDir.toString());
    }

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
    void contextLoads() {
    }

    @Test
    void echoDDDAdapter() throws SSLException, InterruptedException {
        var channel = DDDNettyTLS.createGrpcChannel(clientKeyPair, clientCert, "localhost", port);
        ServiceAdapterServiceGrpc.ServiceAdapterServiceStub stub =
                ServiceAdapterServiceGrpc.newStub(channel);

        // Create a response observer to collect responses
        CountDownLatch latch = new CountDownLatch(1);
        List<ExchangeADUsResponse> responses = new ArrayList<>();
        StreamObserver<ExchangeADUsResponse> responseObserver = new StreamObserver<ExchangeADUsResponse>() {
            @Override
            public void onNext(ExchangeADUsResponse response) {
                responses.add(response);
            }

            @Override
            public void onError(Throwable t) {
                logger.severe("Error in exchangeADUs: " + t.getMessage());
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        StreamObserver<ExchangeADUsRequest> requestObserver = stub.exchangeADUs(responseObserver);

        requestObserver.onNext(ExchangeADUsRequest.newBuilder()
                .setClientId("ben")
                .build());

        requestObserver.onNext(ExchangeADUsRequest.newBuilder()
                .setLastADUIdReceived(0)
                .build());

        requestObserver.onNext(ExchangeADUsRequest.newBuilder()
                .setAdus(AppDataUnit.newBuilder()
                        .setAduId(1)
                        .setData(ByteString.copyFromUtf8("hello"))
                        .build())
                .build());

        requestObserver.onCompleted();
        for (ExchangeADUsResponse response : responses) {
            if (response.hasAdus()) {
                AppDataUnit adu = response.getAdus();

                Assertions.assertEquals(1, adu.getAduId(), "ADU ID should be 1");
                logger.log(INFO, "Received ADU: " + adu.getAduId() + " from client: ben");
                logger.log(INFO, "Received ADU data: " + adu.getData().toStringUtf8());
                Assertions.assertEquals("hello was received", adu.getData().toStringUtf8());
                Assertions.assertEquals(1, response.getLastADUIdReceived());
            }
        }
    }
}