package net.discdd.app.echo;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.ServiceAdapterServiceGrpc;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

/*
 * This is just for testing and takes some shortcuts. We track the clientLastADUId in an in-memory hashmap,
 * so it will be lost if the service is restarted. This is just for testing.
 * Also, we maintain a 1:1 request response, so we use the request ADUId as the response ADUId.
 */
@GrpcService
public class EchoDDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    private static final Logger logger = Logger.getLogger(EchoDDDAdapter.class.getName());
    // this is a quick and dirty implementation of the echo service, so we use an in-memory hashmap
    private final HashMap<String, Long> clientLastADUId = new HashMap<>();

    @Override
    public StreamObserver<ExchangeADUsRequest> exchangeADUs(StreamObserver<ExchangeADUsResponse> responseObserver) {
        return new StreamObserver<ExchangeADUsRequest>() {
            String clientId;
            long lastADUIdReceived;
            LinkedList<AppDataUnit> dataList = new LinkedList<>();

            @Override
            public void onNext(ExchangeADUsRequest request) {
                if (request.hasClientId()) {
                    clientId = request.getClientId();
                }

                if (request.hasAdus()) {
                    var adu = request.getAdus();
                    dataList.add(AppDataUnit.newBuilder()
                            .setAduId(adu.getAduId())
                            .setData(adu.getData()
                                    .concat(ByteString.copyFromUtf8(" was received")))
                            .build());
                    logger.log(INFO,"Received ADU: " + adu.getAduId() + " from client: " + clientId);
                } else {
                    logger.log(INFO,"Received empty ADU from client: " + clientId);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(SEVERE, "Error in exchangeADUs for clientId: " + clientId + throwable.getMessage());
            }

            @Override
            public void onCompleted() {
                if (clientId == null) {
                    logger.log(SEVERE, "Client ID is null. Cannot send response.");
                    return;
                }

                if (dataList.isEmpty()) {
                    logger.log(INFO, "No ADUs received from client: " + clientId);
                }

                for (var adu : dataList) {
                    responseObserver.onNext(ExchangeADUsResponse.newBuilder()
                            .setAdus(adu)
                            .setLastADUIdReceived(clientLastADUId.getOrDefault(clientId, 0L))
                            .build());
                }
                responseObserver.onCompleted();
            }
        };
    }
}
