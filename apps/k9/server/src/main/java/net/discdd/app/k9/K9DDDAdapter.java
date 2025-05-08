package net.discdd.app.k9;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.app.k9.model.LoginAdu;
import net.discdd.app.k9.model.LoginAduAck;
import net.discdd.app.k9.model.RegisterAdu;
import net.discdd.app.k9.model.RegisterAduAck;
import net.discdd.app.k9.repository.K9ClientIdToEmailMappingRepository;
import net.discdd.app.k9.repository.entity.K9ClientIdToEmailMapping;
import net.discdd.app.k9.utils.MailUtils;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.PendingDataCheckRequest;
import net.discdd.grpc.PendingDataCheckResponse;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.model.ADU;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class K9DDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    static final Logger logger = Logger.getLogger(K9DDDAdapter.class.getName());
    private StoreADUs sendADUsStorage;

    private final String APP_ID = "com.fsck.k9.debug";
    private final String RAVLY_DOMAIN = "ravlykmail.com";
    private PasswordEncoder passwordEncoder;
    @Autowired
    private K9ClientIdToEmailMappingRepository clientToEmailRepository;

    public K9DDDAdapter(@Value("${adapter-server.root-dir}") Path rootDir) {
        sendADUsStorage = new StoreADUs(rootDir.resolve("send"), true);
        passwordEncoder = new BCryptPasswordEncoder();
    }

    private void processEmailAdus(AppDataUnit adu) throws IOException {
        var addressList = MailUtils.getToCCBccAddresses(adu.getData().toByteArray());
        for (var address : addressList) {
            String domain = MailUtils.getDomain(address);
            if (RAVLY_DOMAIN.equals(domain)) {
                Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(address);
                if (entity.isPresent()) {
                    String destClientId = entity.get().getClientId();
                    sendADUsStorage.addADU(destClientId, APP_ID, adu.toByteArray(), -1);
                    logger.log(INFO, "Completed processing ADU Id: " + adu.getAduId());
                } else {
                    // TODO: what if email doesn't exist
                }
            } else {
                // TO_DO : Process messages for other domains
                logger.log(WARNING, "Unable to process ADU Id: " + adu.getAduId() + " with domain: " + domain);
            }
        }
    }

    private void processLoginAdus(AppDataUnit adu, String clientId) throws IOException {
        LoginAdu parsedAdu = LoginAdu.parseAdu(adu);
        if (parsedAdu != null) {
            // verify email
            boolean emailExists = clientToEmailRepository.existsById(parsedAdu.getEmail());
            if (emailExists) {
                Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(parsedAdu.getEmail());
                if (entity.isPresent()) {
                    String hashedPassword = entity.get().getPassword();
                    if (passwordEncoder.matches(parsedAdu.getPassword(), hashedPassword)) {
                        // update email clientId entry
                        clientToEmailRepository.updateClientId(clientId, parsedAdu.getEmail());
                        // create success ack
                        LoginAduAck ack = new LoginAduAck(parsedAdu.getEmail(), hashedPassword, true, null);
                        sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
                        return;
                    }
                }
            }
        }

        // create error ack
        LoginAduAck ack = new LoginAduAck(null,
                null,
                false,
                "Either not parsable, email doesn't exist, or password is incorrect.");
        sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
    }

    private void processRegisterAdus(AppDataUnit adu, String clientId) throws IOException {
        RegisterAdu parsedAdu = RegisterAdu.parseAdu(adu);

        if (parsedAdu != null) {
            // generate emails
            String[] emails = parsedAdu.generateEmails();
            for (String email : emails) {
                if (!clientToEmailRepository.existsById(email)) {
                    String hashedPassword = passwordEncoder.encode(parsedAdu.getPassword());
                    K9ClientIdToEmailMapping entity = new K9ClientIdToEmailMapping(email, clientId, hashedPassword);
                    clientToEmailRepository.save(entity);
                    RegisterAduAck ack = new RegisterAduAck(email, hashedPassword, true, null);
                    sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
                    return;
                }
            }
        }

        RegisterAduAck ack =
                new RegisterAduAck(null, null, false, "Either not parsable or all email combinations exist");
        sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
    }

    // This method will parse the ToAddress from the mail ADUs and prepares ADUs for the respective
    // client directories
    private void processADUsToSend(AppDataUnit adu, String clientId) throws IOException {
        byte[] prefixBytes = Arrays.copyOfRange(adu.getData().toByteArray(), 0, 15);
        String dataStr = new String(prefixBytes);

        if (dataStr.startsWith("login")) {
            processLoginAdus(adu, clientId);
        } else if (dataStr.startsWith("register")) {
            processRegisterAdus(adu, clientId);
        } else {
            processEmailAdus(adu);
        }
    }

    @Override
    public StreamObserver<ExchangeADUsRequest> exchangeADUs(StreamObserver<ExchangeADUsResponse> responseObserver) {
        return new StreamObserver<ExchangeADUsRequest>() {
            String clientId = null;
            Long lastADUIdRecvd = null;
            Long lastProcessedADUId = 0L;


            @Override
            public void onNext(ExchangeADUsRequest exchangeADUsRequest) {
                if (exchangeADUsRequest.hasClientId()) {
                    clientId = exchangeADUsRequest.getClientId();
                }

                if (exchangeADUsRequest.hasLastADUIdReceived()) {
                    lastADUIdRecvd = exchangeADUsRequest.getLastADUIdReceived();
                }

                if (exchangeADUsRequest.hasAdus()) {
                    var adu = exchangeADUsRequest.getAdus();
                    try {
                        processADUsToSend(adu, clientId);
                        if (lastProcessedADUId < adu.getAduId()) {
                            lastProcessedADUId = adu.getAduId();
                        }
                    } catch (IOException e) {
                        logger.log(SEVERE, "Error while processing aduId:" + adu.getAduId(), e);
                    }
                    logger.log(INFO, "Received ADUs for clientId: " + clientId);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(SEVERE, "Error while processing ADUs for clientId: " + clientId, throwable);
            }

            @Override
            public void onCompleted() {
                try {
                    sendADUsStorage.deleteAllFilesUpTo(clientId, APP_ID, lastADUIdRecvd);
                    logger.log(INFO, "Deleted all ADUs till Id:" + lastADUIdRecvd);
                } catch (IOException e) {
                    logger.log(SEVERE,
                            String.format("Error while deleting ADUs for client: {} app: {} till AduId: {}",
                                    clientId,
                                    APP_ID,
                                    lastADUIdRecvd),
                            e);
                }

                List<ADU> aduListToReturn = new ArrayList<>();

                try {
                    aduListToReturn = sendADUsStorage.getAppData(clientId, APP_ID);
                } catch (IOException e) {
                    logger.log(SEVERE, "Error fetching ADUs to return for clientId: " + clientId, e);
                }

                try {

                    for (var adu : aduListToReturn) {
                        long aduId = adu.getADUId();
                        var data = sendADUsStorage.getADU(clientId, APP_ID, aduId);
                        responseObserver.onNext(ExchangeADUsResponse.newBuilder()
                                .setAdus(AppDataUnit.newBuilder()
                                        .setData(ByteString.copyFrom(data))
                                        .setAduId(aduId)
                                        .build())
                                .setLastADUIdReceived(lastProcessedADUId)
                                .build());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Error while building response data for clientId: " + clientId, e);
                }
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void pendingDataCheck(PendingDataCheckRequest request,
                                 StreamObserver<PendingDataCheckResponse> responseObserver) {
        List<String> pendingClients = new ArrayList<>();

        sendADUsStorage.getAllClientApps().filter(s -> {
            return sendADUsStorage.getLastADUIdAdded(s.clientId(), s.appId()) >
                    sendADUsStorage.getLastADUIdDeleted(s.clientId(), s.appId());
        }).map(StoreADUs.ClientApp::clientId).forEach(pendingClients::add);

        responseObserver.onNext(PendingDataCheckResponse.newBuilder().addAllClientId(pendingClients).build());
        responseObserver.onCompleted();
    }
}
