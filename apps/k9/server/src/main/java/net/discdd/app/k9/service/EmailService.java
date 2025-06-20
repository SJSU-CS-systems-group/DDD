package net.discdd.app.k9.service;

import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

import static java.lang.String.format;

@Service
public class EmailService implements ApplicationRunner {
    public static final Logger logger = Logger.getLogger(EmailService.class.getName());
    public final String relayHost;
    public final int relayPort;
    public final String localDomain;

    EmailService(@Value("${smtp.relay.host}") String relayHost,
                 @Value("${smtp.relay.port}") int relayPort,
                 @Value("${smtp.localDomain}") String localDomain) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.localDomain = localDomain;
        logger.info(format("EmailService %s initialized relayHost: %s:%s", localDomain, relayHost, relayPort));
    }

    public void sendExternalEmail(MimeMessage mimeMessage) throws Exception {
        var emailBuilder = EmailBuilder.copying(mimeMessage);
        var filteredRecipients = emailBuilder.getRecipients()
                .stream()
                .filter(recipient -> !recipient.getAddress().split("@")[1].equals(localDomain))
                .toList();
        emailBuilder.clearRecipients();
        emailBuilder.withRecipients(filteredRecipients);
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

    }
}