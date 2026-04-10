package net.discdd.app.trick;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.PendingDataCheckRequest;
import net.discdd.grpc.PendingDataCheckResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.model.ADU;
import net.discdd.utils.StoreADUs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * gRPC service adapter for the Trick peer-to-peer messaging app.
 * Pure relay: reads destination_client_id from the TrickADU envelope
 * and routes the payload to that client's outbound queue via StoreADUs.
 *
 * TrickADU is generated from src/main/proto/TrickADU.proto in this module.
 */
@GrpcService
public class TrickDDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {

    private static final Logger logger = Logger.getLogger(TrickDDDAdapter.class.getName());
    public static final String APP_ID = "org.trcky.trick";

    private final StoreADUs sendADUsStorage;

    public TrickDDDAdapter(StoreADUs sendADUsStorage) {
        this.sendADUsStorage = sendADUsStorage;
    }

    @Override
    public void exchangeADUs(ExchangeADUsRequest request,
                             StreamObserver<ExchangeADUsResponse> responseObserver) {
        String clientId = request.getClientId();
        long lastADUIdRecvd = request.getLastADUIdReceived();
        long lastProcessedId = 0L;

        for (AppDataUnit adu : request.getAdusList()) {
            try {
                // TrickADU is generated from message.proto in the Trick app
                TrickADU trickAdu = TrickADU.parseFrom(adu.getData().toByteArray());
                String destClientId = trickAdu.getDestinationClientId();
                byte[] payload = trickAdu.getChatMessage().toByteArray();
                sendADUsStorage.addADU(destClientId, APP_ID, payload, -1);
                lastProcessedId = adu.getAduId();
            } catch (InvalidProtocolBufferException e) {
                logger.warning("Failed to parse TrickADU from client " + clientId + ": " + e.getMessage());
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to store ADU for client " + clientId, e);
            }
        }

        try {
            sendADUsStorage.deleteAllFilesUpTo(clientId, APP_ID, lastADUIdRecvd);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to delete ADUs up to " + lastADUIdRecvd + " for client " + clientId, e);
        }

        List<AppDataUnit> outbound = new ArrayList<>();
        try {
            List<ADU> aduList = sendADUsStorage.getAppData(clientId, APP_ID);
            for (ADU adu : aduList) {
                long aduId = adu.getADUId();
                byte[] data = sendADUsStorage.getADU(clientId, APP_ID, aduId);
                outbound.add(AppDataUnit.newBuilder()
                        .setAduId(aduId)
                        .setData(ByteString.copyFrom(data))
                        .build());
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error fetching ADUs to return for clientId: " + clientId, e);
        }

        responseObserver.onNext(ExchangeADUsResponse.newBuilder()
                .addAllAdus(outbound)
                .setLastADUIdReceived(lastProcessedId)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void pendingDataCheck(PendingDataCheckRequest request,
                                 StreamObserver<PendingDataCheckResponse> responseObserver) {
        List<String> pending = new ArrayList<>();
        sendADUsStorage.getAllClientApps()
                .filter(ca -> sendADUsStorage.getLastADUIdAdded(ca.clientId(), ca.appId()) >
                              sendADUsStorage.getLastADUIdDeleted(ca.clientId(), ca.appId()))
                .map(StoreADUs.ClientApp::clientId)
                .forEach(pending::add);

        responseObserver.onNext(PendingDataCheckResponse.newBuilder()
                .addAllClientId(pending).build());
        responseObserver.onCompleted();
    }
}
