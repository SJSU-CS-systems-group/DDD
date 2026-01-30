package net.discdd.client.bundlerouting;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.whispersystems.libsignal.InvalidKeyException;

import net.discdd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.Constants;

// TODO: I'm not sure if this class is worthwhile. We can easily generate a sequence of needed
// encryptedBundleIds on the fly.
public class ClientWindow {

    private static final Logger logger = Logger.getLogger(ClientWindow.class.getName());

    static private ClientWindow singleClientWindowInstance = null;
    private final LinkedList<UnencryptedBundleId> windowOfUnencryptedBundleIds = new LinkedList<>();
    private final String clientID;
    private final ClientPaths clientPaths;
    private int windowLength = 10; /* Default Value */
    /* Allocate and Initialize Window with provided size
     * Uses default size(10) if provided size is <= 0
     * Parameter:
     * size:    Size of window
     * Returns:
     * None
     */

    private ClientWindow(int length, String clientID, ClientPaths clientPaths) {
        this.clientID = clientID;
        this.clientPaths = clientPaths;

        try {
            initializeWindow();
        } catch (IOException e) {
            logger.log(WARNING, "Failed to initialize Window from Disk -- creating new window" + e);
            if (length > 0) {
                windowLength = length;
            } else {
                logger.log(WARNING, "Invalid window size -- using default size: " + windowLength);
            }
        }
    }

    public static ClientWindow initializeInstance(int windowLength, String clientID, ClientPaths clientPaths)
            throws BufferOverflow, IOException {
        if (singleClientWindowInstance == null) {
            singleClientWindowInstance = new ClientWindow(windowLength, clientID, clientPaths);
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

    /* Generates bundleIDs for window slots
     * Parameter:
     * count            : Number of slots to be filled
     * startCounter     : counter value to begin generating new bundleIDs
     * Returns:
     * None
     */
    private void fillWindow(long startCounter, int count) throws IOException {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            String bundleId = BundleIDGenerator.generateBundleID(this.clientID, i, BundleIDGenerator.DOWNSTREAM);
            windowOfUnencryptedBundleIds.add(new UnencryptedBundleId(bundleId, i));
        }

        updateDBWindow();
    }

    private void updateDBWindow() throws IOException {
        Files.write(clientPaths.dbFile,
                    String.format(Locale.US,
                                  "%d,%d",
                                  windowOfUnencryptedBundleIds.getFirst().bundleCounter(),
                                  windowOfUnencryptedBundleIds.getLast().bundleCounter()).getBytes());

        logger.log(FINE,
                   "Update window: " + windowOfUnencryptedBundleIds.getFirst().bundleCounter() + " - " +
                           windowOfUnencryptedBundleIds.getLast().bundleCounter());
    }

    private void initializeWindow() throws IOException {

        var start = 0L;
        windowLength = Constants.DEFAULT_WINDOW_SIZE;
        var end = start + windowLength - 1;

        try {
            String dbData = new String(Files.readAllBytes(clientPaths.dbFile));
            String[] dbCSV = dbData.split(",");
            start = Long.parseLong(dbCSV[0]);
            end = Long.parseLong(dbCSV[1]);
        } catch (NoSuchFileException e) {
            // this is expected the first time
            logger.log(INFO, "Window File not found -- creating new window");
        } catch (IOException e) {
            logger.log(WARNING, "Failed to read Window from Disk -- creating new window", e);
        }
        fillWindow(start, (int) (end - start + 1));
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

        long begin = windowOfUnencryptedBundleIds.getFirst().bundleCounter();
        long end = windowOfUnencryptedBundleIds.getLast().bundleCounter();
        if (ack < begin) {
            logger.log(FINE, "Received old [" + ack + " < " + begin + "]");
            return;
        } else if (ack > end) {
            logger.log(FINE, "Received Invalid ACK [" + ack + " < " + end + "]");
            return;
        }

        windowOfUnencryptedBundleIds.removeIf(bundle -> bundle.bundleCounter() <= ack);

        fillWindow(end + 1, windowLength - windowOfUnencryptedBundleIds.size());

        logger.log(FINE, "Updated Begin: " + Long.toUnsignedString(begin) + "; End: " + Long.toUnsignedString(end));
    }

    /* Returns the entire window
     * Parameters:
     * None
     * Returns:
     * None
     */
    public List<String> getWindow(ClientSecurity client) throws InvalidKeyException, GeneralSecurityException {
        return windowOfUnencryptedBundleIds.stream().map(ueb -> {
            try {
                return client.encryptBundleID(ueb.bundleId);
            } catch (GeneralSecurityException | InvalidKeyException e) {
                logger.log(SEVERE, "Failed to encrypt bundleID: " + ueb.bundleId, e);
            }
            return null;
        }).collect(Collectors.toList());
    }

    record UnencryptedBundleId(String bundleId, long bundleCounter) {}
}
