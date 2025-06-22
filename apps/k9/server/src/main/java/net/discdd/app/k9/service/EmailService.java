package net.discdd.app.k9.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import net.discdd.app.k9.K9DDDAdapter;
import net.discdd.app.k9.repository.K9ClientIdToEmailMappingRepository;
import net.discdd.utils.StoreADUs;
import net.mailific.main.Main;
import net.mailific.server.ServerConfig;
import net.mailific.server.commands.BaseHandler;
import net.mailific.server.commands.ParsedCommandLine;
import net.mailific.server.netty.NettySmtpServer;
import net.mailific.server.reference.BaseMailObject;
import net.mailific.server.session.Reply;
import net.mailific.server.session.SessionState;
import net.mailific.server.session.SmtpSession;
import net.mailific.server.session.StandardStates;
import net.mailific.server.session.Transition;
import org.apache.james.jspf.impl.DefaultSPF;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;
import static net.discdd.app.k9.K9DDDAdapter.MAX_RECIPIENTS;
import static net.discdd.app.k9.K9DDDAdapter.MAX_RECV_DATA_SIZE;

@Service
public class EmailService implements ApplicationRunner {
    public static final Logger logger = Logger.getLogger(EmailService.class.getName());
    public final String relayHost;
    public final int relayPort;
    public final String localDomain;
    private final DkimConfig dkim;
    private final int localPort;
    private final String tlsCert;
    private final String tlsPrivate;
    private final String tlsPrivatePassword;
    private final ConfigurableApplicationContext context;
    private final K9ClientIdToEmailMappingRepository clientToEmailRepository;
    private final StoreADUs storeADUs;

    EmailService(@Value("${smtp.relay.host}") String relayHost,
                 @Value("${smtp.relay.port}") int relayPort,
                 @Value("${smtp.localDomain}") String localDomain,
                 @Value("${smtp.localPort}") int localPort,
                 @Value("${smtp.dkim.keyFile:#{null}}") String keyFile,
                 @Value("${smtp.tls.cert:#{null}}") String tlsCert,
                 @Value("${smtp.tls.private:#{null}}") String tlsPrivate,
                 @Value("${smtp.tls.privatePassword:#{null}}") String tlsPrivatePassword,
                 K9ClientIdToEmailMappingRepository clientToEmailRepository,
                 StoreADUs storeADUs,
                 ConfigurableApplicationContext context) throws IOException {
        this.context = context;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.localDomain = localDomain.toLowerCase();
        this.localPort = localPort;
        this.tlsCert = tlsCert;
        this.tlsPrivate = tlsPrivate;
        this.tlsPrivatePassword = tlsPrivatePassword;
        this.clientToEmailRepository = clientToEmailRepository;
        this.storeADUs = storeADUs;
        if (keyFile != null) {
            var privateKey = Base64.getDecoder().decode(unPEM(Files.readString(Path.of(keyFile))));
            var dkimBuilder =
                    DkimConfig.builder().excludedHeadersFromDkimDefaultSigningList("From", "Subject") // default is none
                            .dkimPrivateKeyData(privateKey).dkimSigningDomain(localDomain).dkimSelector("mail");
            dkim = dkimBuilder.useLengthParam(true) // default is false
                    .headerCanonicalization(DkimConfig.Canonicalization.SIMPLE) // default is RELAXED
                    .bodyCanonicalization(DkimConfig.Canonicalization.SIMPLE) // default is RELAXED
                    .build();
            logger.info(format("✅ EmailService %s initialized dkim mail selector with keyFile: %s",
                               localDomain,
                               keyFile));
        } else {
            dkim = null;
            logger.info("⚠️ Dkim signing is disabled, no keyFile provided");
        }

        logger.info(format("✅ EmailService %s initialized relayHost: %s:%s", localDomain, relayHost, relayPort));
    }

    public static String unPEM(String pem) {
        return pem.replaceAll("--+[^-]+--+", "").replace("\n", "").replace("\r", "");
    }

    public void sendExternalEmail(MimeMessage mimeMessage) throws Exception {
        var emailBuilder = EmailBuilder.copying(mimeMessage);

        // make sure we are only sending to the non-local recipients
        var filteredRecipients = emailBuilder.getRecipients()
                .stream()
                .filter(recipient -> !recipient.getAddress().split("@")[1].equals(localDomain))
                .toList();
        emailBuilder.clearRecipients();
        emailBuilder.withRecipients(filteredRecipients);
        if (dkim != null) {
            emailBuilder.signWithDomainKey(dkim);
        }
        var email = emailBuilder.buildEmail();

        try (var mailer = MailerBuilder.withSMTPServer(relayHost, relayPort)
                .withTransportStrategy(TransportStrategy.SMTP)
                .withProperty("mail.smtp.localhost", localDomain)
                .withProperty("mail.smtp.starttls.enable", "false")
                .withProperty("mail.smtp.starttls.required", "false")
                .withProperty("mail.smtp.ssl.enable", "false")
                .withDebugLogging(true)
                .buildMailer()) {
            mailer.sendMail(email).get();
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {

        ServerConfig.Builder builder = Main.defaultServerConfigBuilder(s -> new DDDMailObject());
        var tlsEnabled = false;
        if (tlsCert == null) {
            // No TLS certificate provided, so we assume an SMTP proxy that is using the proxy_protocol
            var commandHandlers = Main.baseCommandHandlers(localDomain, localDomain, s -> new DDDMailObject());
            commandHandlers.put("PROXY", new ProxyCommand());
            builder.withCommandHandlers(commandHandlers.values());
            logger.info("⚠️ PROXY command installed.!");
        } else {
            var tlsCertFile = new File(tlsCert);
            var tlsPrivateFile = new File(tlsPrivate);
            if (!tlsCertFile.canRead() || !tlsPrivateFile.canRead()) {
                logger.severe(format(
                        "Both the TLS certificate file (%s) and private key file (%s) must exist and be readable",
                        tlsCert,
                        tlsPrivate));
                SpringApplication.exit(context, () -> 2);
                return;
            }
            builder.withTlsCert(tlsCertFile).withTlsCertKey(tlsPrivateFile);
            if (tlsPrivatePassword != null) {
                builder.withTlsCertPassword(tlsPrivatePassword);
            }
            tlsEnabled = true;
        }
        builder.withListenPort(localPort);
        builder.withListenHost("::");
        new NettySmtpServer(builder.build()).start();

        if (tlsEnabled) {
            logger.info(format("✅ Mailific server with TLS started on port %s", localPort));
        } else {
            logger.warning(format("⚠️ Mailific server WITHOUT TLS started on port %s", localPort));
            logger.warning("⚠️ Assuming an SMTP proxy that is doing the SPF check, so skipping check here!");
        }
    }

    static public class ProxyCommand extends BaseHandler {
        public static final String SESSION_CLIENTIP_PROPERTY = "proxied-client.ip";

        @Override
        protected Transition handleValidCommand(SmtpSession session, String commandLine) {
            var parts = commandLine.split(" ");
            // line is of the form:
            // PROXY TCP4 src_ip dst_ip src_port dst_port
            var clientIp = parts[2];
            session.setProperty(SESSION_CLIENTIP_PROPERTY, clientIp);
            return new Transition(Reply.DO_NOT_REPLY, SessionState.NO_STATE_CHANGE);
        }

        @Override
        protected boolean validForState(SessionState state) {
            return state == StandardStates.CONNECTED;
        }

        @Override
        public String verb() {
            return "PROXY";
        }
    }

    class DDDMailObject extends BaseMailObject {
        DefaultSPF spfValidator = new DefaultSPF();
        List<String> rcptClientIds = new ArrayList<>();
        ByteArrayOutputStream baos;

        private static String stripEmailAddress(String addr) {
            if (addr.contains(">")) {
                int end = addr.indexOf(">");
                int begin = addr.lastIndexOf("<");
                return addr.substring(begin + 1, end);
            }
            return addr;
        }

        @Override
        public Reply mailFrom(ParsedCommandLine mailFrom, SmtpSession session) {
            var superReply = super.mailFrom(mailFrom, session);
            if (superReply.success()) {
                var ehloCommand = session.getEhloCommandLine();
                if (ehloCommand == null) {
                    return new Reply(500, "5.5.1 EHLO required before MAIL FROM");
                }
                var ehloHost = session.getEhloCommandLine().getPath();
                var from = stripEmailAddress(mailFrom.getPath());

                // No TLS private key, so we assume an SMTP proxy is going to send us a proxy command
                var ipAddress = tlsPrivate == null ?
                                (String) session.getProperty(ProxyCommand.SESSION_CLIENTIP_PROPERTY) :
                                session.getRemoteAddress().getAddress().getHostAddress();

                try {
                    var result = spfValidator.checkSPF(ipAddress, from, ehloHost);
                    String status = result.getResult();
                    String explanation = result.getExplanation(); // May be null if no explanation available
                    if (!"pass".equalsIgnoreCase(status)) {
                        String reason = explanation != null ? explanation : "SPF failed";
                        return new Reply(550, "5.7.23 SPF check failed: " + reason);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, format("SPF check failed for %s: %s", from, e.getMessage()), e);
                    return new Reply(550, "5.7.23 SPF check failed: " + e.getMessage());
                }
                logger.log(Level.INFO, format("Receiving mail from %s", from));
            }
            return superReply;
        }

        @Override
        public Reply offerRecipient(ParsedCommandLine rcpt) {
            var addr = stripEmailAddress(rcpt.getPath());
            var parts = addr.split("@");
            if (parts.length != 2 || !parts[1].toLowerCase().equals(localDomain)) {
                return new Reply(550, "5.1.6 Recipient is not in the local domain");
            }
            var rec = clientToEmailRepository.findById(addr);
            logger.log(Level.INFO, format("Mail for %s going to %s", addr, rec));
            if (rec.isEmpty()) {
                return new Reply(550, "5.1.1 Recipient does not exist");
            }
            rcptClientIds.add(rec.get().clientId);
            if (rcptClientIds.size() > MAX_RECIPIENTS) {
                return new Reply(550, "5.1.1 Recipient exceeds maximum number of recipients");
            }
            return super.offerRecipient(rcpt);
        }

        @Override
        public Reply complete(SmtpSession session) {
            var data = baos.toByteArray();
            try {
                var message = new MimeMessage(null, new ByteArrayInputStream(data));
                // TODO: we should really really check the SMTP from with the message from!!!
                String[] dkimHeaders = message.getHeader("DKIM-Signature");
                if (dkimHeaders != null && dkimHeaders.length > 0) {
                    logger.info("Found dkim headers, skipping verification");
                }

            } catch (MessagingException e) {
                logger.log(Level.INFO, format("Failed to parse message from received data: %s", e.getMessage()), e);
            }
            // create those ADUs!
            try {
                for (var clientId : rcptClientIds) {
                    storeADUs.addADU(clientId, K9DDDAdapter.APP_ID, data, -1);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, format("Failed to store ADUs for %s", rcptClientIds), e);
                return new Reply(550, "4.3.1 Failed to store ADUs");
            }
            return super.complete(session);
        }

        @Override
        public void writeLine(byte[] line, int offset, int length) throws IOException {
            if (baos.size() + length > MAX_RECV_DATA_SIZE) {
                throw new IOException(format("Exceeded maximum data size %s", MAX_RECV_DATA_SIZE));
            }
            baos.write(line, offset, length);
            super.writeLine(line, offset, length);
        }

        @Override
        public void prepareForData(SmtpSession session) {
            baos = new ByteArrayOutputStream();
            super.prepareForData(session);
        }
    }

}