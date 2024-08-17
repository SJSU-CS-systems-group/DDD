package net.discdd.server.bundlerouting;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.server.bundletransmission.BundleTransmission;
import net.discdd.server.repository.ServerWindowRepository;
import net.discdd.server.repository.entity.ServerWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

@Service
public class ServerWindowService {

    private final ServerWindowRepository serverwindowrepo;

    @Autowired
    public ServerWindowService(ServerWindowRepository serverWindowRepository) {
        this.serverwindowrepo = serverWindowRepository;
    }

    private static final Logger logger = Logger.getLogger(ServerWindowService.class.getName());

    @Autowired
    ServerSecurity serverSecurity;

    private ServerWindow getValueFromTable(String clientID) {
        var window = serverwindowrepo.findByClientID(clientID);
        return window == null ? new ServerWindow(clientID, 1, 1, BundleTransmission.WINDOW_LENGTH) : window;
    }

    /**
     * If a new bundle counter is generated, it will be returned, otherwise returns -currentCounter
     */
    public long generateNewBundleCounter(String clientID) {
        var window = getValueFromTable(clientID);
        if (window.getCurrentCounter() >= window.getStartCounter() + window.getWindowLength()) {
            return -window.getCurrentCounter();
        }
        window.setCurrentCounter(window.getCurrentCounter() + 1);
        serverwindowrepo.save(window);
        return window.getCurrentCounter();
    }

    public boolean isWindowFull(String clientId) {
        var window = getValueFromTable(clientId);
        return window.getCurrentCounter() >= window.getStartCounter() + window.getWindowLength();
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (encrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackedBundleID) throws GeneralSecurityException, InvalidKeyException {
        String decryptedBundleID = null;
        try {
            decryptedBundleID = serverSecurity.decryptBundleID(ackedBundleID, clientID);
        } catch (InvalidClientIDException e) {
            throw new RuntimeException(e);
        }
        logger.log(WARNING, "[ServerWindow]: Decrypted Ack from file = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        ServerWindow serverWindow = getValueFromTable(clientID);
        if (ack > serverWindow.getStartCounter()) {
            serverWindow.setStartCounter(ack);
            serverwindowrepo.save(serverWindow);
        }
    }
}
