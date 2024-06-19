package com.ddd.client.bundlerouting;

import static java.nio.charset.StandardCharsets.UTF_8;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import com.ddd.bundlerouting.WindowUtils.CircularBuffer;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.bundlesecurity.BundleIDGenerator;
import com.ddd.client.bundlesecurity.ClientSecurity;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class ClientWindow {

    private static final Logger logger = Logger.getLogger(ClientWindow.class.getName());

    static private ClientWindow singleClientWindowInstance = null;
    final private Path clientWindowDataPath;
    final private String WINDOW_FILE = "clientWindow.csv";

    private final CircularBuffer window;
    private String clientID = null;
    private int windowLength = 10; /* Default Value */

    /* Begin and End are used as Unsigned Long */
    private long begin = 0;
    private long end = 0;

    /* Generates bundleIDs for window slots
     * Parameter:
     * count            : Number of slots to be filled
     * startCounter     : counter value to begin generating new bundleIDs
     * Returns:
     * None
     */
    private void fillWindow(int count, long startCounter) throws BufferOverflow, IOException {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            window.add(BundleIDGenerator.generateBundleID(this.clientID, i, BundleIDGenerator.DOWNSTREAM));
        }

        end = begin + windowLength;
        updateDBWindow();
    }

    private void updateDBWindow() throws IOException {
        var dbFile = clientWindowDataPath.resolve(WINDOW_FILE);

        Files.write(dbFile, String.format(Locale.US, "%d,%d,%d", begin, end, windowLength).getBytes());
    }

    private void initializeWindow() throws IOException {
        var dbFile = clientWindowDataPath.resolve(WINDOW_FILE);

        String dbData = new String(Files.readAllBytes(dbFile), UTF_8);

        String[] dbCSV = dbData.split(",");

        begin = Long.parseLong(dbCSV[0]);
        end = Long.parseLong(dbCSV[1]);
        windowLength = Integer.parseInt(dbCSV[2]);
    }

    /* Allocate and Initialize Window with provided size
     * Uses default size(10) if provided size is <= 0
     * Parameter:
     * size:    Size of window
     * Returns:
     * None
     */
    private ClientWindow(int length, String clientID, Path rootPath) throws InvalidLength, BufferOverflow, IOException {
        clientWindowDataPath = rootPath.resolve("ClientWindow");
        clientWindowDataPath.toFile().mkdirs();

        try {
            initializeWindow();
        } catch (IOException e) {
            logger.log(WARNING, "Failed to initialize Window from Disk -- creating new window\n" + e);
            if (length > 0) {
                windowLength = length;
            } else {
                logger.log(WARNING, "Invalid window size -- using default size: " + windowLength);
            }
        }

        this.clientID = clientID;
        window = new CircularBuffer(windowLength);

        /* Initialize Slots */
        fillWindow(windowLength, begin);
    }

    public static ClientWindow initializeInstance(int windowLength, String clientID, Path rootPath) throws InvalidLength, BufferOverflow, IOException {
        if (singleClientWindowInstance == null) {
            singleClientWindowInstance = new ClientWindow(windowLength, clientID, rootPath);
        } else {
            logger.log(INFO, "[WIN]: Client Window Instance is already initialized!");
        }
        return singleClientWindowInstance;
    }

    public static ClientWindow getInstance() {
        if (singleClientWindowInstance == null) {
            throw new IllegalStateException("[WIN]: Client Window has not been initialized!");
        }
        return singleClientWindowInstance;
    }

    /* Updates the window based on the Received bundleID
     * Parameters:
     * bundleID    : BundleID (encrypted)
     * Returns:
     * None
     */
    public void processBundle(String bundleID, ClientSecurity clientSecurity) throws BufferOverflow, IOException,
            GeneralSecurityException, InvalidKeyException {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        logger.log(FINE, "Largest Bundle ID = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        if (Long.compareUnsigned(ack, begin) == -1) {
            logger.log(FINE, "Received old [" + ack + " < " + begin + "]");
            return;
        } else if (Long.compareUnsigned(ack, end) == 1) {
            logger.log(FINE, "Received Invalid ACK [" + ack + " < " + end + "]");
            return;
        }

        /* Index will be an int as windowLength is int */
        int ackIndex = (int) Long.remainderUnsigned(ack, windowLength);

        /* Delete ACKs until ackIndex */
        int noDeleted = 0;
        try {
            noDeleted = window.deleteUntilIndex(ackIndex);
        } catch (InvalidLength e) {
            logger.log(WARNING, "Received Invalid ACK [" + Long.toUnsignedString(ack) + "]");
        }

        begin = ack + 1;
        /* Add new bundleIDs to window */
        fillWindow(noDeleted, end);

        logger.log(FINE, "Updated Begin: " + Long.toUnsignedString(begin) + "; End: " + Long.toUnsignedString(end));
    }

    /* Returns the entire window
     * Parameters:
     * None
     * Returns:
     * None
     */
    public List<String> getWindow(ClientSecurity client) throws InvalidKeyException, GeneralSecurityException {
        List<String> bundleIDs = window.getBuffer();

        for (int i = 0; i < bundleIDs.size(); ++i) {
            String bundleID = client.encryptBundleID(bundleIDs.get(i));
            bundleIDs.set(i, bundleID);
        }

        return bundleIDs;
    }
}
