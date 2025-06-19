package net.discdd.app.k9.utils;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class MailUtils {
    final static Logger logger = Logger.getLogger(MailUtils.class.getName());

    final static Properties emptyProperties = new Properties();

    public static MimeMessage getMimeMessage(InputStream is) throws MessagingException {
        return new MimeMessage(Session.getInstance(emptyProperties), is);
    }

    private static void collectAddrs(List<Address> dest, Address[] list) {
        if (list != null) {
            dest.addAll(Arrays.asList(list));
        }
    }

    public static List<Address> getToCCBccAddresses(MimeMessage message) {
        List<Address> addressList = new ArrayList<>();
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
