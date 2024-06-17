package net.discdd.app.echo;

import io.grpc.ManagedChannelBuilder;
import net.discdd.server.ServiceAdapterGrpc;
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
        ServiceAdapterGrpc.ServiceAdapterBlockingStub stub = ServiceAdapterGrpc.newBlockingStub(
                ManagedChannelBuilder.forAddress("localhost", 9090).usePlaintext().build());
        var prepRsp = stub.prepareData(net.discdd.server.ClientData.newBuilder().setClientId("ben").build());
        Assertions.assertEquals(net.discdd.server.StatusCode.SUCCESS, prepRsp.getCode());
        var saveRsp = stub.saveData(net.discdd.server.AppData.newBuilder().setClientId("ben").addDataList(
                net.discdd.server.AppDataUnit.newBuilder().setAduId(1)
                        .setData(com.google.protobuf.ByteString.copyFromUtf8("hello")).build()).build());
        Assertions.assertEquals(1, saveRsp.getLastADUIdReceived());
        Assertions.assertEquals(1, saveRsp.getDataListCount());
        Assertions.assertEquals(1, saveRsp.getDataList(0).getAduId());
        Assertions.assertEquals("hello was received", saveRsp.getDataList(0).getData().toStringUtf8());
    }
}
