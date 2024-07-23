package net.discdd.app.k9;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.app.k9.utils.MailUtils;
import net.discdd.model.ADU;
import net.discdd.server.AppData;
import net.discdd.server.AppDataUnit;
import net.discdd.server.ClientData;
import net.discdd.server.PrepareResponse;
import net.discdd.server.ServiceAdapterGrpc;
import net.discdd.server.StatusCode;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;


@GrpcService
public class K9DDDAdapter extends ServiceAdapterGrpc.ServiceAdapterImplBase {
    final static Logger logger = Logger.getLogger(K9DDDAdapter.class.getName());
    private StoreADUs sendADUsStorage;

    @Value("${spring.application.name}")
    private String appId;

    public K9DDDAdapter(@Value("${k9-server.root-dir}") Path rootDir) {
        sendADUsStorage = new StoreADUs(rootDir.resolve("send").toFile().toPath(), true);
    }

    // This method will parse the ToAddress from the mail ADUs and prepares ADUs for the respective client directories
    private void processADUsToSend(AppDataUnit adu) throws IOException {
        var toAddressList = MailUtils.getToAddresses(adu.getData().toByteArray());
        for (var clientId : toAddressList) {
            sendADUsStorage.addADU(MailUtils.getLocalAddress(clientId), appId, adu.toByteArray(), -1);
        }
    }

    @Override
    public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
        String clientId = request.getClientId();
        Long lastADUIdRecvd = request.getLastADUIdReceived();
        var aduListRecvd = request.getDataListList();

        for (AppDataUnit adu : aduListRecvd) {
            try {
                processADUsToSend(adu);
            } catch (IOException e) {
                logger.log(SEVERE, "Error while processing aduId:" + adu.getAduId(), e);
            }
        }

        try {
            sendADUsStorage.deleteAllFilesUpTo(clientId, appId, lastADUIdRecvd);
        } catch (IOException e) {
            logger.log(SEVERE, String.format("Error while deleting ADUs for client: {} app: {} till AduId: {}",clientId, appId, lastADUIdRecvd), e);
        }

        List<AppDataUnit> dataListToReturn = new ArrayList<>();
        List<ADU> aduListToReturn = new ArrayList<>();

        try {
            aduListToReturn = sendADUsStorage.getAllADUsToSend(clientId, appId);
        } catch (IOException e) {
            logger.log(SEVERE, "Error fetching ADUs to return for clientId: " + clientId, e);
        }

        try {
            for (var adu : aduListToReturn) {
                long aduId = adu.getADUId();
                var data = sendADUsStorage.getADU(clientId, appId, aduId);
                dataListToReturn.add(AppDataUnit.newBuilder().setData(ByteString.copyFrom(data))
                        .setAduId(aduId).build());
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Error while building response data for clientId: " + clientId, e);
        }

        responseObserver.onNext(AppData.newBuilder().setClientId(request.getClientId())
                .addAllDataList(dataListToReturn).build());
        responseObserver.onCompleted();
    }

    @Override
    public void prepareData(ClientData request, StreamObserver<PrepareResponse> responseObserver) {
        responseObserver.onNext(PrepareResponse.newBuilder().setCode(StatusCode.SUCCESS).build());
        responseObserver.onCompleted();
    }
}
