package net.discdd.app.echo;

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
class EchoApplicationTests {
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
            clientCert = DDDTLSUtil.getSelfSignedCertificate(clientKeyPair, DDDTLSUtil.publicKeyToName(clientKeyPair.getPublic()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void contextLoads() {
    }


    @Test
    void echoDDDAdapter() throws SSLException {
        var channel = DDDNettyTLS.createGrpcChannel(clientKeyPair, clientCert, "localhost", port);

        ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub stub = ServiceAdapterServiceGrpc.newBlockingStub(channel);
        var saveRsp = stub.exchangeADUs(ExchangeADUsRequest.newBuilder().setClientId("ben").addAdus(
                AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("hello")).build()).build());
        Assertions.assertEquals(1, saveRsp.getLastADUIdReceived());
        Assertions.assertEquals(1, saveRsp.getAdusCount());
        Assertions.assertEquals(1, saveRsp.getAdus(0).getAduId());
        Assertions.assertEquals("hello was received", saveRsp.getAdus(0).getData().toStringUtf8());
    }
}
