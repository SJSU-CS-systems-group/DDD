package net.discdd.app.trick;

import com.google.protobuf.ByteString;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
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

@SpringBootTest
class TrickApplicationTests {

    @Value("${ssl-grpc.server.port}")
    private int port;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("adapter-server.root-dir", () -> tempDir.toString());
    }

    private static final KeyPair clientKeyPair;
    private static final X509Certificate clientCert;

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

    /**
     * Sends a TrickADU from clientA addressed to clientB, then verifies
     * clientB can retrieve it via a second exchangeADUs call.
     */
    @Test
    void routesMessageFromClientAToClientB() throws Exception {
        var channel = DDDNettyTLS.createGrpcChannel(clientKeyPair, clientCert, "localhost", port);
        ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub stub =
                ServiceAdapterServiceGrpc.newBlockingStub(channel);

        // Build a TrickADU: clientA sends a message destined for clientB
        TrickADU trickAdu = TrickADU.newBuilder()
                .setDestinationClientId("clientB")
                .setChatMessage(ByteString.copyFromUtf8("hello from clientA"))
                .build();

        // clientA uploads the ADU
        var sendRsp = stub.exchangeADUs(ExchangeADUsRequest.newBuilder()
                .setClientId("clientA")
                .addAdus(AppDataUnit.newBuilder()
                        .setAduId(1)
                        .setData(ByteString.copyFrom(trickAdu.toByteArray()))
                        .build())
                .build());

        Assertions.assertEquals(1, sendRsp.getLastADUIdReceived());

        // clientB checks its inbox — should have the message
        var recvRsp = stub.exchangeADUs(ExchangeADUsRequest.newBuilder()
                .setClientId("clientB")
                .setLastADUIdReceived(0)
                .build());

        Assertions.assertEquals(1, recvRsp.getAdusCount(), "clientB should have 1 pending ADU");

        // The payload stored is just the raw chat_message bytes
        String payload = recvRsp.getAdus(0).getData().toStringUtf8();
        Assertions.assertEquals("hello from clientA", payload);
    }
}
