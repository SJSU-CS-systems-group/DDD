package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;

import java.util.HashMap;
import java.util.LinkedList;

/*
 * This is just for testing and takes some shortcuts. We track the clientLastADUId in an in-memory hashmap,
 * so it will be lost if the service is restarted. This is just for testing.
 * Also, we maintain a 1:1 request response, so we use the request ADUId as the response ADUId.
 */
@GrpcService
public class EchoDDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    // this is a quick and dirty implementation of the echo service, so we use an in-memory hashmap
    private final HashMap<String, Long> clientLastADUId = new HashMap<>();

    @Override
    public void exchangeADUs(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> responseObserver) {
        LinkedList<AppDataUnit> dataList = new LinkedList<>();
        request.getAdusList().forEach(data -> dataList.add(AppDataUnit.newBuilder().setAduId(data.getAduId()).setData(
                data.getData().concat(ByteString.copyFromUtf8(" was received"))).build()));
        if (!dataList.isEmpty()) {
            var lastADUId = dataList.getLast().getAduId();
            clientLastADUId.compute(request.getClientId(), (k, v) -> v == null || v < lastADUId ? lastADUId : v);
        }
        responseObserver.onNext(ExchangeADUsResponse.newBuilder().addAllAdus(dataList)
                                        .setLastADUIdReceived(clientLastADUId.getOrDefault(request.getClientId(), 0L))
                                        .build());
        responseObserver.onCompleted();
    }
}
