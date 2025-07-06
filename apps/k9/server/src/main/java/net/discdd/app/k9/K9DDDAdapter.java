package net.discdd.app.k9;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.app.k9.common.ControlAdu;
import net.discdd.app.k9.repository.K9ClientIdToEmailMappingRepository;
import net.discdd.app.k9.repository.entity.K9ClientIdToEmailMapping;
import net.discdd.app.k9.service.EmailService;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@GrpcService
public class K9DDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {

    static final Logger logger = Logger.getLogger(K9DDDAdapter.class.getName());
    public static final int MAX_RECIPIENTS = 5;
    // yahoo and gmail are 25M and MS is 20M
    public static final int MAX_DATA_SIZE = 1024 * 1024 * 20;
    // we will allow extermin incoming mails that are 25% more than MAX_DATA_SIZE
    public static final int MAX_RECV_DATA_SIZE = (int) (MAX_DATA_SIZE * 1.25);
    public static final String APP_ID = "net.discdd.k9";
    private final Random rand = new Random();
    private final StoreADUs sendADUsStorage;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    @Autowired
    private K9ClientIdToEmailMappingRepository clientToEmailRepository;

    public K9DDDAdapter(StoreADUs sendADUsStorage, EmailService emailService) {
        this.sendADUsStorage = sendADUsStorage;
        passwordEncoder = new BCryptPasswordEncoder();
        this.emailService = emailService;
    }

    private void processEmailAdus(AppDataUnit adu, String clientId) {
        var bouncedMessage = new StringBuilder();
        var originalMessage = adu.getData().toByteArray();
        try {
            var mimeMessage = MailUtils.getMimeMessage(new ByteArrayInputStream(originalMessage));
            var addressList = MailUtils.getToCCBccAddresses(mimeMessage);
            if (addressList.size() > MAX_RECIPIENTS) {
                bouncedMessage.append(format("Emails cannot have more than %s recipients\n", MAX_RECIPIENTS));
            } else {
                var externalDomains = false;
                for (var address : addressList) {
                    var stringAddress = address.getAddress();
                    if (stringAddress == null) {
                        bouncedMessage.append(format("%s does not have an address\n", address));
                        continue;
                    }
                    String domain = MailUtils.getDomain(address);
                    if (emailService.localDomain.equals(domain)) {
                        Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(stringAddress);
                        if (entity.isPresent()) {
                            String destClientId = entity.get().clientId;
                            sendADUsStorage.addADU(destClientId, APP_ID, adu.toByteArray(), -1);
                            logger.log(INFO, "Completed processing ADU Id: " + adu.getAduId());
                        } else {
                            bouncedMessage.append(format("%s does not exist.\n", address));
                        }
                    } else {
                        externalDomains = true;
                    }
                }
                if (externalDomains) {
                    emailService.sendExternalEmail(mimeMessage);
                }
            }
        } catch (Exception e) {
            logger.log(SEVERE, format("Error processing email ADU for client %s: %s", clientId, e.getMessage()), e);
            bouncedMessage.append(format("Could not send message. %s\n", e.getMessage()));
            if (e.getCause() != null) {
                bouncedMessage.append(format("  %s\n", e.getCause().getMessage()));

            }
        }
        if (!bouncedMessage.isEmpty()) {

            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);
            try {
                MimeMessage bounceMessage = createBounceMessage(session, bouncedMessage, originalMessage);
                var baos = new ByteArrayOutputStream();
                bounceMessage.writeTo(baos);
                sendADUsStorage.addADU(clientId, APP_ID, baos.toByteArray(), -1);
            } catch (Exception e) {
                logger.log(SEVERE, format("Error bouncing email from %s: %s", clientId, e.getMessage()));
            }

        }
    }

    private MimeMessage createBounceMessage(Session session,
                                            StringBuilder bouncedMessage,
                                            byte[] originalMessage) throws MessagingException {
        MimeMessage bounceMessage = new MimeMessage(session);

        // Set bounce message headers
        bounceMessage.setFrom(new InternetAddress("mailer-daemon@" + emailService.localDomain));
        bounceMessage.setSubject("Mail delivery failed");

        // Create the bounce message content
        String bounceText = String.format("""
                                                  Your message could not be delivered.
                                                                                                    
                                                  Reason: %s
                                                                                                    
                                                  Original message: %s""",
                                          bouncedMessage,
                                          new String(originalMessage, 0, Math.min(1024, originalMessage.length)));
        bounceMessage.setText(bounceText);
        return bounceMessage;
    }

    private void processLoginAdus(ControlAdu.LoginControlAdu adu, String clientId) throws IOException {
        // verify email
        boolean emailExists = clientToEmailRepository.existsById(adu.email());
        if (emailExists) {
            Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(adu.email());
            if (entity.isPresent()) {
                String hashedPassword = entity.get().password;
                if (passwordEncoder.matches(adu.password(), hashedPassword)) {
                    // update email clientId entry
                    clientToEmailRepository.updateClientId(clientId, adu.email());
                    // create success ack
                    var ack = new ControlAdu.LoginAckControlAdu(Map.of("email", adu.email(), "success", true));
                    sendADUsStorage.addADU(clientId, APP_ID, ack.toBytes(), -1);
                    return;
                }
            }
        }

        // create error ack
        var ack = new ControlAdu.LoginAckControlAdu(Map.of("message", "Email doesn't exist or password is incorrect."));
        sendADUsStorage.addADU(clientId, APP_ID, ack.toBytes(), -1);
    }

    private char getRandNum() {
        return (char) ('0' + rand.nextInt(10));
    }

    private char getRandChar() {
        return (char) ('a' + rand.nextInt(26));
    }

    private void processRegisterAdus(ControlAdu.RegisterControlAdu adu, String clientId) throws IOException {
        String message = null;
        if (!isLowerCaseASCII(adu.prefix()) || !isLowerCaseASCII(adu.suffix()) || adu.prefix().length() > 10 ||
                adu.prefix().length() < 3 || adu.suffix().length() > 10 || adu.suffix().length() < 3) {
            message = "Prefix and suffix must be 3 to 10 lower case ASCII characters only.";
        }
        int tries = 0;
        while (message == null) {
            var email =
                    adu.prefix() + getRandNum() + getRandChar() + getRandChar() + getRandNum() + adu.suffix() + '@' +
                            emailService.localDomain;
            if (!clientToEmailRepository.existsById(email)) {
                String hashedPassword = passwordEncoder.encode(adu.password());
                K9ClientIdToEmailMapping entity = new K9ClientIdToEmailMapping(email, clientId, hashedPassword);
                clientToEmailRepository.save(entity);
                var ack = new ControlAdu.RegisterAckControlAdu(Map.of("email", email, "success", true));
                sendADUsStorage.addADU(clientId, APP_ID, ack.toBytes(), -1);
                return;
            }
            if (tries++ > 100) {
                message = "Could not generate a unique email address. Please try with different prefix or suffix.";
            }
        }
        var ack = new ControlAdu.RegisterAckControlAdu(Map.of("message", message));
        sendADUsStorage.addADU(clientId, APP_ID, ack.toBytes(), -1);
    }

    private boolean isLowerCaseASCII(String fix) {
        return fix.chars().allMatch(c -> 'a' <= c && c <= 'z');
    }

    // This method will parse the ToAddress from the mail ADUs and prepares ADUs for the respective
    // client directories
    private void processADUsToSend(AppDataUnit adu, String clientId) throws IOException {
        var bytes = adu.getData().toByteArray();
        if (ControlAdu.isControlAdu(bytes)) {
            try {
                var controlAdu = ControlAdu.fromBytes(bytes);
                if (controlAdu instanceof ControlAdu.LoginControlAdu loginControlAdu) {
                    processLoginAdus(loginControlAdu, clientId);
                } else if (controlAdu instanceof ControlAdu.RegisterControlAdu registerControlAdu) {
                    processRegisterAdus(registerControlAdu, clientId);
                } else if (controlAdu instanceof ControlAdu.WhoAmIControlAdu whoAmIControlAdu) {
                    processWhoAmIAdu(whoAmIControlAdu, clientId);
                } else {
                    logger.log(SEVERE, "Unknown control ADU type: " + controlAdu.getClass().getName());
                }
            } catch (Exception e) {
                logger.log(SEVERE, "Error processing control ADU for clientId: " + clientId, e);
                sendADUsStorage.addADU(clientId, APP_ID, ("ERROR\n" + e.getMessage()).getBytes(), -1);
            }
        } else {
            processEmailAdus(adu, clientId);
        }
    }

    private void processWhoAmIAdu(ControlAdu.WhoAmIControlAdu ignored, String clientId) throws IOException {
        String email = clientToEmailRepository.findById(clientId).map(rec -> rec.email).orElse("");
        var ack = new ControlAdu.WhoAmIAckControlAdu(Map.of("email", email, "success", !email.isEmpty()));
        sendADUsStorage.addADU(clientId, APP_ID, ack.toBytes(), -1);

    }

    @Override
    public void exchangeADUs(ExchangeADUsRequest request, StreamObserver<ExchangeADUsResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.log(INFO, "Received ADUs for clientId: " + clientId);
        var lastADUIdRecvd = request.getLastADUIdReceived();
        var aduListRecvd = request.getAdusList();
        var lastProcessedADUId = 0L;
        for (AppDataUnit adu : aduListRecvd) {
            try {
                processADUsToSend(adu, clientId);
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
                       format("Error while deleting ADUs for client: %s app: %s till AduId: %s",
                              clientId,
                              APP_ID,
                              lastADUIdRecvd),
                       e);
        }

        List<AppDataUnit> dataListToReturn = new ArrayList<>();
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
                dataListToReturn.add(AppDataUnit.newBuilder()
                                             .setData(ByteString.copyFrom(data))
                                             .setAduId(aduId)
                                             .build());
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Error while building response data for clientId: " + clientId, e);
        }

        responseObserver.onNext(ExchangeADUsResponse.newBuilder()
                                        .addAllAdus(dataListToReturn)
                                        .setLastADUIdReceived(lastProcessedADUId)
                                        .build());

        responseObserver.onCompleted();
    }

    @Override
    public void pendingDataCheck(PendingDataCheckRequest request,
                                 StreamObserver<PendingDataCheckResponse> responseObserver) {
        List<String> pendingClients = new ArrayList<>();

        sendADUsStorage.getAllClientApps()
                .filter(s -> sendADUsStorage.getLastADUIdAdded(s.clientId(), s.appId()) >
                        sendADUsStorage.getLastADUIdDeleted(s.clientId(), s.appId()))
                .map(StoreADUs.ClientApp::clientId)
                .forEach(pendingClients::add);

        responseObserver.onNext(PendingDataCheckResponse.newBuilder().addAllClientId(pendingClients).build());
        responseObserver.onCompleted();
    }
}
