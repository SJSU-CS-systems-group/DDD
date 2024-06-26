package com.ddd.server.bundlerouting;

import com.ddd.bundlerouting.WindowUtils.CircularBuffer;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.ClientAlreadyExists;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.InvalidBundleID;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;
import com.ddd.bundlesecurity.BundleIDGenerator;
import com.ddd.server.bundlesecurity.InvalidClientIDException;
import com.ddd.server.bundlesecurity.ServerSecurity;
import com.ddd.server.repository.ServerWindowRepository;
import com.ddd.server.repository.entity.ServerWindow;
import com.ddd.server.storage.SNRDatabases;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.annotation.PostConstruct;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
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
    HashMap<String, CircularBuffer> clientWindowMap = null;
    ServerSecurity serverSecurity = null;
    private SNRDatabases database = null;
    private static final String dbTableName = "ServerWindow";
    private static final String STARTCOUNTER = "startCounter";
    private static final String ENDCOUNTER = "endCounter";
    private static final String WINDOW_LENGTH = "windowLength";

    @Autowired
    private Environment env;

    @PostConstruct
    public void init() throws SQLException {
        // TODO: Change to config
        String url = env.getProperty("spring.datasource.url");
        String uname = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String dbName = env.getProperty("spring.datasource.db-name");

        database = new SNRDatabases(url, uname, password, dbName);

        try {
            initializeWindow();
        } catch (SQLException | BufferOverflow | InvalidLength e) {
            logger.log(SEVERE, "[ServerWindow] INFO: Failed to initialize window from database", e);

            String dbTableCreateQuery =
                    "CREATE TABLE " + dbTableName + " " + "(clientID VARCHAR(256) not NULL," + STARTCOUNTER +
                            " VARCHAR(256)," + ENDCOUNTER + " VARCHAR(256)," + WINDOW_LENGTH + " INTEGER," +
                            "PRIMARY KEY (clientID))";

            database.createTable(dbTableCreateQuery);
        }
    }

    private void initializeWindow() throws SQLException, InvalidLength, BufferOverflow {
        //String query =
        //      "SELECT clientID, " + STARTCOUNTER + ", " + ENDCOUNTER + ", " + WINDOW_LENGTH + " FROM " + dbTableName;

        Iterable<ServerWindow> entities = serverwindowrepo.findAll();

        for (ServerWindow entity : entities) {
            String clientID = entity.getClientID();
            long startCounter = Long.parseLong(entity.getStartCounter());
            long endCounter = Long.parseLong(entity.getEndCounter());
            int windowLength = entity.getWindowLength();

            // for (String[] result : results) {
            //   String clientID = result[0];
            // long startCounter = Long.parseLong(result[1]);
            //long endCounter = Long.parseLong(result[2]);
            //int windowLength = Integer.parseInt(result[3]);

            CircularBuffer circularBuffer = createBuffer(clientID, startCounter, endCounter, windowLength);
            clientWindowMap.put(clientID, circularBuffer);
        }
    }

    private CircularBuffer createBuffer(String clientID, long startCounter, long endCounter, int windowLength) throws BufferOverflow, InvalidLength {
        CircularBuffer circularBuffer = new CircularBuffer(windowLength);

        for (long i = startCounter; i < endCounter; ++i) {
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

    private String getValueFromTable(String clientID, String columnName) throws SQLException {
        String query = "SELECT " + columnName + " FROM " + dbTableName + " WHERE clientID = '" + clientID + "'";

        String[] result = (database.getFromTable(query)).get(0);

        return result[0];
    }

    private void updateValueInTable(String clientID, String columnName, String value) {
        String updateQuery =
                "UPDATE " + dbTableName + " SET " + columnName + " = '" + value + "'" + " WHERE clientID = '" +
                        clientID + "'";

        try {
            database.updateEntry(updateQuery);
        } catch (SQLException e) {
            logger.log(SEVERE, "[ServerWindow]: Failed to update Server Window DB!");
            e.printStackTrace();
        }
    }

    private void initializeEntry(String clientID, int windowLength) {
        String insertQuery =
                "INSERT INTO " + dbTableName + " VALUES " + "('" + clientID + "', '0', '0', " + windowLength + ")";

        try {
            database.insertIntoTable(insertQuery);
        } catch (SQLException e) {
            logger.log(SEVERE, "[ServerWindow]: Failed to Initalize Client [" + clientID + "]to Server Window DB!");
            e.printStackTrace();
        }
    }

    /* Add a new client and initialize its window
     * Parameters:
     * clientID     : encoded clientID
     * windowLength : length of the window to be created
     * Returns:
     * None
     */
    public void addClient(String clientID, int windowLength) throws InvalidLength, ClientAlreadyExists {
        if (clientWindowMap.containsKey(clientID)) {
            throw new ClientAlreadyExists("[ServerWindow]: Cannot Add to Map; client already exists");
        }
        clientWindowMap.put(clientID, new CircularBuffer(windowLength));
        initializeEntry(clientID, windowLength);
    }

    /* Commits the bundleID to the client's window
     * Parameter:
     * clientID     : encoded clientID
     * bundleID     : encoded bundleID
     * Returns:
     * None
     */
    public void updateClientWindow(String clientID, String bundleID) throws ClientWindowNotFound, BufferOverflow,
            InvalidBundleID, SQLException, InvalidClientIDException, GeneralSecurityException, InvalidKeyException {
        String decryptedBundleID = null;
        decryptedBundleID = serverSecurity.decryptBundleID(bundleID, clientID);

        CircularBuffer circularBuffer = getClientWindow(clientID);

        long bundleIDcounter =
                BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);
        long endCounter = Long.parseUnsignedLong(getValueFromTable(clientID, ENDCOUNTER));

        if (endCounter != bundleIDcounter) {
            throw new InvalidBundleID("[ServerWindow]: Expected: " + Long.toUnsignedString(endCounter) + ", Got: " +
                                              Long.toUnsignedString(bundleIDcounter));
        }

        circularBuffer.add(decryptedBundleID);
        endCounter++;
        updateValueInTable(clientID, ENDCOUNTER, Long.toUnsignedString(endCounter));
    }

    public String getCurrentbundleID(String clientID) throws InvalidClientIDException, SQLException,
            GeneralSecurityException, InvalidKeyException {
        long endCounter = Long.parseUnsignedLong(getValueFromTable(clientID, ENDCOUNTER));

        String plainBundleID = BundleIDGenerator.generateBundleID(clientID, endCounter, BundleIDGenerator.DOWNSTREAM);
        return serverSecurity.encryptBundleID(plainBundleID, clientID);
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (encrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackedBundleID) throws ClientWindowNotFound, InvalidLength,
            InvalidClientIDException, GeneralSecurityException, InvalidKeyException {
        CircularBuffer circularBuffer = getClientWindow(clientID);
        String decryptedBundleID = serverSecurity.decryptBundleID(ackedBundleID, clientID);
        logger.log(WARNING, "[ServerWindow]: Decrypted Ack from file = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        try {
            compareBundleID(ack, clientID);

            int index = (int) Long.remainderUnsigned(ack, circularBuffer.getLength());
            circularBuffer.deleteUntilIndex(index);
            long startCounter = ack + 1;
            updateValueInTable(clientID, STARTCOUNTER, Long.toUnsignedString(startCounter));

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
        long startCounter = Long.parseUnsignedLong(getValueFromTable(clientID, STARTCOUNTER));
        long endCounter = Long.parseUnsignedLong(getValueFromTable(clientID, ENDCOUNTER));

        if (Long.compareUnsigned(ack, startCounter) == -1) {
            throw new RecievedOldACK(
                    "Received old ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(startCounter) +
                            "]");
        } else if (Long.compareUnsigned(ack, endCounter) == 1) {
            throw new RecievedInvalidACK(
                    "Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(endCounter) +
                            "]");
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