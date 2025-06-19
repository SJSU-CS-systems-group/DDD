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

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class K9DDDAdapter extends ServiceAdapterServiceGrpc.ServiceAdapterServiceImplBase {
    static final Logger logger = Logger.getLogger(K9DDDAdapter.class.getName());
    public static final int MAX_RECIPIENTS = 5;
    private final String APP_ID = "net.discdd.k9";
    private final String RAVLYK_DOMAIN = "ravlykmail.com";
    private final Random rand = new Random();
    private final StoreADUs sendADUsStorage;
    private final PasswordEncoder passwordEncoder;
    @Autowired
    private K9ClientIdToEmailMappingRepository clientToEmailRepository;

    public K9DDDAdapter(@Value("${adapter-server.root-dir}") Path rootDir) {
        sendADUsStorage = new StoreADUs(rootDir.resolve("send"), true);
        passwordEncoder = new BCryptPasswordEncoder();
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
                for (var addr : addressList) {
                    if (!(addr instanceof InternetAddress address)) {
                        bouncedMessage.append(format("%s is not a valid internet address\n", addr));
                        continue;
                    }
                    var stringAddress = address.getAddress();
                    if (stringAddress == null) {
                        bouncedMessage.append(format("%s does not have an address\n", address));
                        continue;
                    }
                    String domain = MailUtils.getDomain(address);
                    if (RAVLYK_DOMAIN.equals(domain)) {
                        Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(stringAddress);
                        if (entity.isPresent()) {
                            String destClientId = entity.get().clientId;
                            sendADUsStorage.addADU(destClientId, APP_ID, adu.toByteArray(), -1);
                            logger.log(INFO, "Completed processing ADU Id: " + adu.getAduId());
                        } else {
                            bouncedMessage.append(format("%s does not exist.\n", address));
                        }
                    } else {
                        // TO_DO : Process messages for other domains
                        logger.log(WARNING, "Unable to process ADU Id: " + adu.getAduId() + " with domain: " + domain);
                        bouncedMessage.append(format("cannot send email to %s\n", domain));
                    }
                }
            }
        } catch (Exception e) {
            bouncedMessage.append("Could not parse message.");
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
        bounceMessage.setFrom(new InternetAddress("mailer-daemon@" + RAVLYK_DOMAIN));
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

    private void processLoginAdus(AppDataUnit adu, String clientId) throws IOException {
        LoginAdu parsedAdu = LoginAdu.parseAdu(adu);
        if (parsedAdu != null) {
            // verify email
            boolean emailExists = clientToEmailRepository.existsById(parsedAdu.getEmail());
            if (emailExists) {
                Optional<K9ClientIdToEmailMapping> entity = clientToEmailRepository.findById(parsedAdu.getEmail());
                if (entity.isPresent()) {
                    String hashedPassword = entity.get().password;
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

    private char getRandNum() {
        return (char) ('0' + rand.nextInt(10));
    }

    private char getRandChar() {
        return (char) ('a' + rand.nextInt(26));
    }

    private void processRegisterAdus(AppDataUnit adu, String clientId) throws IOException {
        RegisterAdu parsedAdu = RegisterAdu.parseAdu(adu);
        final String message;

        if (parsedAdu == null) {
            message = "Registration is not parsable";
        } else if (parsedAdu.password.length() < 8) {
            message = "Password is less than 8 characters";
        } else if (parsedAdu.prefixes.length < 1 || parsedAdu.suffixes.length < 1) {
            message = "No prefixes or suffixes found";
        } else if (parsedAdu.prefixes[0].length() < 3 || parsedAdu.suffixes[0].length() < 3) {
            message = "Prefix or suffix is less than 3 characters";
        } else if (!isLowerCaseASCII(parsedAdu.prefixes[0]) || !isLowerCaseASCII(parsedAdu.suffixes[0])) {
            message = "Prefix and suffix should only contain a-z characters";
        } else {
            while (true) {
                var email = parsedAdu.prefixes[0] + getRandNum() + getRandChar() + getRandChar() + getRandNum() +
                        parsedAdu.suffixes[0] + '@' + RAVLYK_DOMAIN;
                if (!clientToEmailRepository.existsById(email)) {
                    String hashedPassword = passwordEncoder.encode(parsedAdu.password);
                    K9ClientIdToEmailMapping entity = new K9ClientIdToEmailMapping(email, clientId, hashedPassword);
                    clientToEmailRepository.save(entity);
                    RegisterAduAck ack = new RegisterAduAck(email, hashedPassword, true, null);
                    sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
                    return;
                }
            }
        }
        RegisterAduAck ack = new RegisterAduAck(null, null, false, message);
        sendADUsStorage.addADU(clientId, APP_ID, ack.toByteArray(), -1);
    }

    private boolean isLowerCaseASCII(String fix) {
        return fix.chars().allMatch(c -> 'a' <= c && c <= 'z');
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
            processEmailAdus(adu, clientId);
        }
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
