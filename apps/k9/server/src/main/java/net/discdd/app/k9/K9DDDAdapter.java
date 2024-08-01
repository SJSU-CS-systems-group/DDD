package net.discdd.app.k9;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.app.k9.utils.MailUtils;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.model.ADU;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

@GrpcService
public class K9DDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    final static Logger logger = Logger.getLogger(K9DDDAdapter.class.getName());
    private StoreADUs sendADUsStorage;

    private final String APP_ID = "com.fsck.k9.debug";
    private final String RAVLY_DOMAIN = "ravlykmail.com";

    public K9DDDAdapter(@Value("${k9-server.root-dir}") Path rootDir) {
        sendADUsStorage = new StoreADUs(rootDir.resolve("send"), true);
    }

    // This method will parse the ToAddress from the mail ADUs and prepares ADUs for the respective client directories
    private void processADUsToSend(AppDataUnit adu) throws IOException {
        var addressList = MailUtils.getToCCBccAddresses(adu.getData().toByteArray());
        for (var address : addressList) {
            String domain = MailUtils.getDomain(address);
            if (RAVLY_DOMAIN.equals(domain)) {
                sendADUsStorage.addADU(MailUtils.getLocalAddress(address), APP_ID, adu.toByteArray(), -1);
                logger.log(INFO, "Completed processing ADU Id: " + adu.getAduId());
            } else {
                //TO_DO : Process messages for other domains
                logger.log(WARNING, "Unable to process ADU Id: " + adu.getAduId() + " with domain: " + domain);
            }

        }
    }

    @Override
    public void exchangeADUs(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> responseObserver) {
        String clientId = request.getClientId();
        Long lastADUIdRecvd = request.getLastADUIdReceived();
        var aduListRecvd = request.getAdusList();
        Long lastProcessedADUId = 0L;
        for (AppDataUnit adu : aduListRecvd) {
            try {
                processADUsToSend(adu);
                if (lastProcessedADUId < adu.getAduId()) {
                    lastProcessedADUId = adu.getAduId();
                }
            } catch (IOException e) {
                logger.log(SEVERE, "Error while processing aduId:" + adu.getAduId(), e);
            }
        }

        try {
            sendADUsStorage.deleteAllFilesUpTo(clientId, APP_ID, lastADUIdRecvd);
            logger.log(INFO, "Deleted all ADUs till Id:" + lastADUIdRecvd);
        } catch (IOException e) {
            logger.log(SEVERE,
                       String.format("Error while deleting ADUs for client: {} app: {} till AduId: {}", clientId,
                                     APP_ID, lastADUIdRecvd), e);
        }

        List<AppDataUnit> dataListToReturn = new ArrayList<>();
        List<ADU> aduListToReturn = new ArrayList<>();

        try {
            aduListToReturn = sendADUsStorage.getAllADUsToSend(clientId, APP_ID);
        } catch (IOException e) {
            logger.log(SEVERE, "Error fetching ADUs to return for clientId: " + clientId, e);
        }

        try {
            for (var adu : aduListToReturn) {
                long aduId = adu.getADUId();
                var data = sendADUsStorage.getADU(clientId, APP_ID, aduId);
                dataListToReturn.add(
                        AppDataUnit.newBuilder().setData(ByteString.copyFrom(data)).setAduId(aduId).build());
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Error while building response data for clientId: " + clientId, e);
        }

        responseObserver.onNext(
                ExchangeADUsResponse.newBuilder().addAllAdus(dataListToReturn).setLastADUIdReceived(lastProcessedADUId)
                        .build());

        responseObserver.onCompleted();
    }
}
