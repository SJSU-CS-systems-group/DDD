package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.server.AppData;
import net.discdd.server.AppDataUnit;
import net.discdd.server.ClientData;
import net.discdd.server.PrepareResponse;
import net.discdd.server.ServiceAdapterGrpc;
import net.discdd.server.StatusCode;

import java.util.HashMap;
import java.util.LinkedList;

/*
 * This is just for testing and takes some shortcuts. We track the clientLastADUId in an in-memory hashmap,
 * so it will be lost if the service is restarted. This is just for testing.
 * Also, we maintain a 1:1 request response, so we use the request ADUId as the response ADUId.
 */
@GrpcService
public class EchoDDDAdapter extends ServiceAdapterGrpc.ServiceAdapterImplBase {
    // this is a quick and dirty implementation of the echo service, so we use an in-memory hashmap
    private final HashMap<String, Long> clientLastADUId = new HashMap<>();

    @Override
    public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
        LinkedList<AppDataUnit> dataList = new LinkedList<>();
        request.getDataListList().forEach(data -> dataList.add(AppDataUnit.newBuilder().setAduId(data.getAduId())
                                                                       .setData(data.getData()
                                                                                        .concat(ByteString.copyFromUtf8(
                                                                                                " was received")))
                                                                       .build()));
        if (!dataList.isEmpty()) {
            var lastADUId = dataList.getLast().getAduId();
            clientLastADUId.compute(request.getClientId(), (k, v) -> v == null || v < lastADUId ? lastADUId : v);
        }
        responseObserver.onNext(AppData.newBuilder().setClientId(request.getClientId()).addAllDataList(dataList)
                                        .setLastADUIdReceived(clientLastADUId.getOrDefault(request.getClientId(), 0L))
                                        .build());
        responseObserver.onCompleted();
    }

    @Override
    public void prepareData(ClientData request, StreamObserver<PrepareResponse> responseObserver) {
        responseObserver.onNext(PrepareResponse.newBuilder().setCode(StatusCode.SUCCESS).build());
        responseObserver.onCompleted();
    }
}
