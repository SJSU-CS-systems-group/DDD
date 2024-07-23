package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EchoApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void echoDDDAdapter() {
        ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub stub = ServiceAdapterServiceGrpc.newBlockingStub(
                ManagedChannelBuilder.forAddress("localhost", 9090).usePlaintext().build());
        var saveRsp = stub.exchangeADUs(ExchangeADUsRequest.newBuilder().setClientId("ben").addAdus(
                AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFromUtf8("hello")).build()).build());
        Assertions.assertEquals(1, saveRsp.getLastADUIdReceived());
        Assertions.assertEquals(1, saveRsp.getAdusCount());
        Assertions.assertEquals(1, saveRsp.getAdus(0).getAduId());
        Assertions.assertEquals("hello was received", saveRsp.getAdus(0).getData().toStringUtf8());
    }
}
