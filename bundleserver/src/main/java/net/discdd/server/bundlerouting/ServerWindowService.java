package net.discdd.server.bundlerouting;

import net.discdd.bundlerouting.WindowUtils.CircularBuffer;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.server.repository.ServerWindowRepository;
import net.discdd.server.repository.entity.ServerWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.annotation.PostConstruct;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@Service
public class ServerWindowService {

    private final ServerWindowRepository serverwindowrepo;

    @Autowired
    public ServerWindowService(ServerWindowRepository serverWindowRepository) {
        this.serverwindowrepo = serverWindowRepository;
    }

    private static final Logger logger = Logger.getLogger(ServerWindowService.class.getName());
    HashMap<String, CircularBuffer> clientWindowMap = new HashMap<>();

    @Autowired
    ServerSecurity serverSecurity;

    @PostConstruct
    public void init() {
        try {
            initializeWindow();
        } catch (SQLException | BufferOverflow e) {
            logger.log(SEVERE, "[ServerWindow] INFO: Failed to initialize window from database", e);
        }
    }

    private void initializeWindow() throws SQLException, BufferOverflow {

        Iterable<ServerWindow> entities = serverwindowrepo.findAll();

        for (ServerWindow entity : entities) {
            String clientID = entity.getClientID();
            long startCounter = entity.getStartCounter();
            long currentCounter = entity.getCurrentCounter();
            int windowLength = entity.getWindowLength();
            if (currentCounter - startCounter > windowLength) {
                logger.log(SEVERE, String.format(
                        "Current Counter %d  & Start Counter %d > Window Length %d making start equal to current",
                        currentCounter, startCounter, windowLength));
                startCounter = currentCounter;
            }
            CircularBuffer circularBuffer = createBuffer(clientID, startCounter, currentCounter, windowLength);
            clientWindowMap.put(clientID, circularBuffer);
        }
    }

    private CircularBuffer createBuffer(String clientID, long startCounter, long currentCounter, int windowLength) throws BufferOverflow {
        CircularBuffer circularBuffer = new CircularBuffer(windowLength);

        for (long i = startCounter; i < currentCounter; ++i) {
            String bundleID = BundleIDGenerator.generateBundleID(clientID, i, BundleIDGenerator.DOWNSTREAM);
            if (i == startCounter) {
                circularBuffer.initializeFromIndex(bundleID, (int) Long.remainderUnsigned(i, windowLength));
            } else {
                circularBuffer.add(bundleID);
            }
        }
        return circularBuffer;
    }

    /* Returns the window for the requested client
     * Parameters:
     * clientID     : encoded clientID
     * Returns:
     * CircularBuffer object
     */
    private CircularBuffer getClientWindow(String clientID) throws ClientWindowNotFound {
        if (!clientWindowMap.containsKey(clientID)) {
            throw new ClientWindowNotFound("[ServerWindow]: ClientID[" + clientID + "] Not Found");
        }

        return clientWindowMap.get(clientID);
    }

    private ServerWindow getValueFromTable(String clientID) {
        var window = serverwindowrepo.findByClientID(clientID);
        return window == null ? new ServerWindow(clientID, 0, 0, 5) : window;
    }

    private void updateStartCounter(String clientID, long startCounter) {
        ServerWindow serverWindow = getValueFromTable(clientID);
        serverWindow.setStartCounter(startCounter);
        serverwindowrepo.save(serverWindow);
    }

    private void updateCurrentCounter(String clientID, long currentCounter) {
        ServerWindow serverWindow = getValueFromTable(clientID);
        serverWindow.setCurrentCounter(currentCounter);
        serverwindowrepo.save(serverWindow);
    }

    private void initializeEntry(String clientID, int windowLength) {
        ServerWindow serverWindow = new ServerWindow(clientID, 0, 0, windowLength);

        serverwindowrepo.save(serverWindow);
    }

    /* Add a new client and initialize its window
     * Parameters:
     * clientID     : encoded clientID
     * windowLength : length of the window to be created
     * Returns:
     * None
     */
    public void addClient(String clientID, int windowLength) {
        if (clientWindowMap.containsKey(clientID)) {
            return;
        }
        clientWindowMap.put(clientID, new CircularBuffer(windowLength));
        initializeEntry(clientID, windowLength);
    }

    public String getCurrentBundleID(String clientID) throws InvalidClientIDException, GeneralSecurityException,
            InvalidKeyException {
        long currentCounter = getValueFromTable(clientID).getCurrentCounter();

        String plainBundleID =
                BundleIDGenerator.generateBundleID(clientID, currentCounter, BundleIDGenerator.DOWNSTREAM);

        currentCounter++;
        updateCurrentCounter(clientID, currentCounter);
        return serverSecurity.encryptBundleID(plainBundleID, clientID);
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (encrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackedBundleID) throws ClientWindowNotFound,
            GeneralSecurityException, InvalidKeyException {
        CircularBuffer circularBuffer = getClientWindow(clientID);
        String decryptedBundleID = null;
        try {
            decryptedBundleID = serverSecurity.decryptBundleID(ackedBundleID, clientID);
        } catch (InvalidClientIDException e) {
            throw new RuntimeException(e);
        }
        logger.log(WARNING, "[ServerWindow]: Decrypted Ack from file = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        try {
            compareBundleID(ack, clientID);

            int index = (int) Long.remainderUnsigned(ack, circularBuffer.getLength());
            circularBuffer.deleteUntilIndex(index);
            long startCounter = ack + 1;
            updateStartCounter(clientID, startCounter);

            // TODO: Change to log
            logger.log(INFO, "[ServerWindow]: Updated start Counter: " + startCounter);
        } catch (RecievedOldACK | RecievedInvalidACK e) {
            logger.log(SEVERE, "[ServerWindow]: Received Old/Invalid ACK!");
            e.printStackTrace();
        } catch (SQLException e) {
            logger.log(SEVERE, "[ServerWindow]: Failed to update Database!");
            e.printStackTrace();
        }
    }

    private void compareBundleID(long ack, String clientID) throws RecievedOldACK, RecievedInvalidACK, SQLException {
        long startCounter = getValueFromTable(clientID).getStartCounter();
        long currentCounter = getValueFromTable(clientID).getCurrentCounter();

        if (Long.compareUnsigned(ack, startCounter) == -1) {
            throw new RecievedOldACK(
                    "Received old ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(startCounter) +
                            "]");
        } else if (Long.compareUnsigned(ack, currentCounter) == 1) {
            throw new RecievedInvalidACK("Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " +
                                                 Long.toUnsignedString(currentCounter) + "]");
        }
    }

    /* Check if window is full
     *
     */
    public boolean isClientWindowFull(String clientID) throws ClientWindowNotFound {
        return getClientWindow(clientID).isBufferFull();
    }

    public int compareBundleIDs(String id1, String id2, String clientID, boolean direction) throws InvalidClientIDException, GeneralSecurityException, InvalidKeyException {
        String decryptedBundleID1 = serverSecurity.decryptBundleID(id1, clientID);
        String decryptedBundleID2 = serverSecurity.decryptBundleID(id2, clientID);

        return BundleIDGenerator.compareBundleIDs(decryptedBundleID1, decryptedBundleID2, direction);
    }

    public long getCounterFromBundleID(String bundleID, String clientID, boolean direction) throws InvalidClientIDException, GeneralSecurityException, InvalidKeyException {
        String decryptedBundleID = serverSecurity.decryptBundleID(bundleID, clientID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}
