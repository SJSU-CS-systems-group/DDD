package net.discdd.app.k9.utils;

import static java.util.logging.Level.SEVERE;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class MailUtils {
    final static Logger logger = Logger.getLogger(MailUtils.class.getName());

    final static Properties emptyProperties = new Properties();

    public static MimeMessage getMimeMessage(InputStream is) throws MessagingException {
        return new MimeMessage(Session.getInstance(emptyProperties), is);
    }

    private static void collectAddrs(List<InternetAddress> dest, Address[] list) {
        if (list != null) {
            for (Address addr : list) {
                if (addr instanceof InternetAddress address) {
                    dest.add(address);
                }
            }
        }
    }

    public static List<InternetAddress> getToCCBccAddresses(MimeMessage message) {
        List<InternetAddress> addressList = new ArrayList<>();
        try {
            collectAddrs(addressList, message.getRecipients(MimeMessage.RecipientType.TO));
            collectAddrs(addressList, message.getRecipients(MimeMessage.RecipientType.CC));
            collectAddrs(addressList, message.getRecipients(MimeMessage.RecipientType.BCC));
        } catch (Exception e) {
            logger.log(SEVERE, "Error parsing mail to fetch To, CC and BCCAddresses", e);
        }
        return addressList;
    }

    public static String getLocalAddress(InternetAddress address) {
        try {
            return address.getAddress().split("@")[0];
        } catch (Exception e) {
            return "";
        }
    }

    public static String getDomain(InternetAddress address) {
        try {
            return address.getAddress().split("@")[1];
        } catch (Exception e) {
            return "";
        }
    }

}
