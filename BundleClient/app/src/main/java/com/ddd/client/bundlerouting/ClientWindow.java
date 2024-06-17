package com.ddd.client.bundlerouting;

import com.ddd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;

import android.util.Log;
import com.ddd.bundlerouting.WindowUtils.CircularBuffer;
import com.ddd.client.bundlesecurity.BundleIDGenerator;
import com.ddd.client.bundlesecurity.ClientSecurity;
import com.ddd.client.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.client.bundlesecurity.SecurityUtils;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClientWindow {

    private static final Logger logger = Logger.getLogger(ClientWindow.class.getName());

    static private ClientWindow singleClientWindowInstance = null;
    private String clientWindowDataPath = null;
    final private String windowFile = "clientWindow.csv";

    public static final String TAG = "dddDebug";

    private CircularBuffer window = null;
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
    private void fillWindow(int count, long startCounter) throws BufferOverflow {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            window.add(BundleIDGenerator.generateBundleID(this.clientID, i, BundleIDGenerator.DOWNSTREAM));
        }

        end = begin + windowLength;
        updateDBWindow();
    }

    private void updateDBWindow() {
        String dbFile = clientWindowDataPath + File.separator + windowFile;

        try (FileOutputStream stream = new FileOutputStream(dbFile)) {
            String metadata = Long.toUnsignedString(begin) + "," + Long.toUnsignedString(end) + "," + windowLength;
            stream.write(metadata.getBytes());
        } catch (IOException e) {
            logger.log(WARNING, "Error: Failed to write Window to file! " + e);
        }
    }

    private void initializeWindow() throws IOException {
        String dbFile = clientWindowDataPath + File.separator + windowFile;

        String dbData = new String(SecurityUtils.readFromFile(dbFile), StandardCharsets.UTF_8);

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
    private ClientWindow(int length, String clientID, String rootPath) throws InvalidLength, BufferOverflow {
        clientWindowDataPath = rootPath + File.separator + "ClientWindow";

        SecurityUtils.createDirectory(clientWindowDataPath);

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

    public static ClientWindow initializeInstance(int windowLength, String clientID, String rootPath) throws InvalidLength, BufferOverflow {
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
    public void processBundle(String bundleID, ClientSecurity clientSecurity) throws BufferOverflow,
            BundleIDCryptographyException {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        logger.log(FINE, "Largest Bundle ID = " + decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        if (Long.compareUnsigned(ack, begin) == -1) {
            logger.log(FINE,
                       "Received old [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(begin) + "]");
            return;
        } else if (Long.compareUnsigned(ack, end) == 1) {
            logger.log(FINE,
                       "Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(end) +
                               "]");
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
    public List<String> getWindow(ClientSecurity client) throws BundleIDCryptographyException {
        List<String> bundleIDs = window.getBuffer();

        for (int i = 0; i < bundleIDs.size(); ++i) {
            String bundleID = client.encryptBundleID(bundleIDs.get(i));
            bundleIDs.set(i, bundleID);
        }

        return bundleIDs;
    }
}