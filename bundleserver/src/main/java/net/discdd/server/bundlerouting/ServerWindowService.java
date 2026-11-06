package net.discdd.server.bundlerouting;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.ServerWindowRepository;
import net.discdd.server.repository.entity.ServerWindow;
import net.discdd.utils.Constants;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * The server's view of each client's downstream window. A client accepts only downstream counters in
 * {@code acked+1 .. acked+}{@link Constants#DEFAULT_WINDOW_SIZE}, where {@code acked} is the last counter it processed
 * ({@code ClientWindow}; initially 0..9), so the server must not issue a counter beyond that.
 */
@Service
public class ServerWindowService {

    private final ServerWindowRepository serverwindowrepo;
    private final ClientDownstreamAckRepository clientDownstreamAckRepository;
    private final Optional<ServerSecurity> serverSecurity;

    @Autowired
    public ServerWindowService(ServerWindowRepository serverWindowRepository,
                               ClientDownstreamAckRepository clientDownstreamAckRepository,
                               ObjectProvider<ServerSecurity> serverSecurityProvider) {
        this.serverwindowrepo = serverWindowRepository;
        this.clientDownstreamAckRepository = clientDownstreamAckRepository;
        this.serverSecurity = serverSecurityProvider.getIfAvailable() != null ?
                              Optional.of(serverSecurityProvider.getIfAvailable()) :
                              Optional.empty();
    }

    private static final Logger logger = Logger.getLogger(ServerWindowService.class.getName());

    private ServerWindow getValueFromTable(String clientID) {
        var window = serverwindowrepo.findByClientID(clientID);
        return window == null ? new ServerWindow(clientID, 1, 1, Constants.DEFAULT_WINDOW_SIZE) : window;
    }

    /**
     * Whether the client's window holds no counter above {@code lastSentCounter}, so a new downstream bundle could
     * not be accepted and the last one must be re-offered until the client acks.
     */
    public boolean isWindowFull(String clientId, long lastSentCounter) {
        return lastSentCounter >= ackedCounter(clientId) + Constants.DEFAULT_WINDOW_SIZE;
    }

    /**
     * The highest downstream counter the client acked: {@code ClientDownstreamAck.ackedCounter} or the persisted
     * {@code ServerWindow.startCounter}, whichever is larger. With neither it is -1, matching the client's initial
     * window 0..9. A {@code startCounter} of 1 or less is the row's default, not an ACK ({@link #processACK} saves
     * only an increase above it), so it is ignored.
     */
    long ackedCounter(String clientId) {
        long acked = clientDownstreamAckRepository.findById(clientId).map(a -> a.ackedCounter).orElse(-1L);
        var window = serverwindowrepo.findByClientID(clientId);
        return window == null || window.getStartCounter() <= 1 ? acked : Math.max(acked, window.getStartCounter());
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (encrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackedBundleID) throws GeneralSecurityException, InvalidKeyException,
            InvalidClientIDException, IOException {
        String decryptedBundleID = null;
        decryptedBundleID = serverSecurity.get().decryptBundleID(ackedBundleID, clientID);
        logger.log(FINE, "[ServerWindow]: Decrypted Ack from file = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        ServerWindow serverWindow = getValueFromTable(clientID);
        if (ack > serverWindow.getStartCounter()) {
            serverWindow.setStartCounter(ack);
            serverwindowrepo.save(serverWindow);
        }
    }
}
