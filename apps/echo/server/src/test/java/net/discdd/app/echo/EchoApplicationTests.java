package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.security.AdapterSecurity;
import net.discdd.tls.DDDNettyTLS;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;

@SpringBootTest
class EchoApplicationTests {
    @Value("${grpc.server.port}")
    private int port;

    @TempDir
    Path tempDir;

    @Test
    void contextLoads() {
    }

    @Test
    void echoDDDAdapter() throws IOException, InvalidAlgorithmParameterException, CertificateException, NoSuchAlgorithmException, NoSuchProviderException, OperatorCreationException {
        var adapterSecurity = AdapterSecurity.getInstance(tempDir);
        var channel = DDDNettyTLS.createGrpcChannel(adapterSecurity.getAdapterKeyPair(), adapterSecurity.getAdapterCert(), "localhost", port);
        ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub stub = ServiceAdapterServiceGrpc.newBlockingStub(channel);
        var saveRsp = stub.exchangeADUs(ExchangeADUsRequest.newBuilder().setClientId("ben").addAdus(
                AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("hello")).build()).build());
        Assertions.assertEquals(1, saveRsp.getLastADUIdReceived());
        Assertions.assertEquals(1, saveRsp.getAdusCount());
        Assertions.assertEquals(1, saveRsp.getAdus(0).getAduId());
        Assertions.assertEquals("hello was received", saveRsp.getAdus(0).getData().toStringUtf8());
    }
}
