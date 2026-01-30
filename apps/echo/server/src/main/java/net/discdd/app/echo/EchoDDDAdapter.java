package net.discdd.app.echo;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.google.protobuf.ByteString;

import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.ServiceAdapterServiceGrpc;

import io.grpc.stub.StreamObserver;

/*
 * This is just for testing and takes some shortcuts. We track the clientLastADUId in an in-memory hashmap,
 * so it will be lost if the service is restarted. This is just for testing.
 * Also, we maintain a 1:1 request response, so we use the request ADUId as the response ADUId.
 */
@GrpcService
public class EchoDDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    // this is a quick and dirty implementation of the echo service, so we use an in-memory hashmap
    private final HashMap<String, Long> clientLastADUId = new HashMap<>();

    @Bean
    @Qualifier("grpcExecutor")
    public Executor grpcExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    @Override
    public void exchangeADUs(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> responseObserver) {
        LinkedList<AppDataUnit> dataList = new LinkedList<>();
        request.getAdusList()
                .forEach(data -> dataList.add(AppDataUnit.newBuilder()
                        .setAduId(data.getAduId())
                        .setData(data.getData().concat(ByteString.copyFromUtf8(" was received")))
                        .build()));
        if (!dataList.isEmpty()) {
            var lastADUId = dataList.getLast().getAduId();
            clientLastADUId.compute(request.getClientId(), (k, v) -> v == null || v < lastADUId ? lastADUId : v);
        }
        responseObserver.onNext(ExchangeADUsResponse.newBuilder()
                .addAllAdus(dataList)
                .setLastADUIdReceived(clientLastADUId.getOrDefault(request.getClientId(), 0L))
                .build());
        responseObserver.onCompleted();
    }
}
