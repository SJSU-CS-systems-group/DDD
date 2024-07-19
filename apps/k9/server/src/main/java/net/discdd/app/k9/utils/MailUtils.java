package net.discdd.app.k9.utils;

import javax.mail.Address;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class MailUtils {
    final static Logger logger = Logger.getLogger(MailUtils.class.getName());
    public static List<String> getToAddresses(byte[] rawEmail) {
        List<String> toAddressList = new ArrayList<>();
        try {
            // Set up a mail session with default properties
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);

            // Create a MimeMessage from the raw email bytes
            MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(rawEmail));

            // Get the "to" address
            Address[] toAddresses = message.getRecipients(MimeMessage.RecipientType.TO);
            if (toAddresses != null && toAddresses.length > 0) {
                for (Address address: toAddresses) {
                    toAddressList.add(((InternetAddress) address).getAddress());
                }
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Error parsing mail to fetch To-Addresses", e);
        }
        return toAddressList;
    }

    public static String getLocalAddress(String address) {
        return address.split("@")[0];
    }

    public static String getDomain(String address) {
        return address.split("@")[1];
    }

}
