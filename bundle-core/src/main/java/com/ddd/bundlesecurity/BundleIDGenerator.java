package com.ddd.bundlesecurity;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import com.google.common.primitives.Bytes;
import org.whispersystems.libsignal.InvalidKeyException;

public class BundleIDGenerator {
    public static final boolean UPSTREAM = true;
    public static final boolean DOWNSTREAM = false;
    /* Length of the Counter */
    private static final int counterLength = 8;

    /* Counter value used as unsigned long */
    private long currentCounter = 0;

    /* Generates bundleID
     * Used when a window is NOT involved to maintain the counter values
     * Parameters:
     * clientKeyPath:   Path to client Identity key (public key)
     * direction:   UPSTREAM (Client->Server), DOWNSTREAM (Server->Client)
     * Returns:
     * bundleID as a Base64 encoded String
     */
    synchronized public String generateBundleID(Path clientKeyPath, boolean direction) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException {
        String clientID = SecurityUtils.getClientID(clientKeyPath);

        currentCounter++;

        return generateBundleID(clientID, currentCounter, direction);
    }

    /* Generates bundleID for the specified clientID and counter value
     * Used when a window is involved to maintain the counter values
     * Parameters:
     * clientID:    ID of the client
     * counter:     counter value
     * direction:   UPSTREAM (Client->Server), DOWNSTREAM (Server->Client)
     * Returns:
     * bundleID as a Base64 encoded String
     */
    public static String generateBundleID(String clientID, long counter, boolean direction) {
        byte[] bClientID = Base64.getUrlDecoder().decode(clientID);
        byte[] bCounter = new byte[1];
        bCounter[0] = (byte) counter;
        byte[] bundleID = new byte[bClientID.length + counterLength];

        if (direction == UPSTREAM) {
            bundleID = Bytes.concat(bClientID, bCounter);
        } else {
            bundleID = Bytes.concat(bCounter, bClientID);
        }

        return Base64.getUrlEncoder().encodeToString(bundleID);
    }

    /* Compares BundleIDs
     * Paramerters:
     * id1:         First BundleID
     * id2:         Second BundleID
     * direction:   UPSTREAM (Client->Server), DOWNSTREAM (Server->Client)
     * Returns:
     * -1 =>  id1 < id2
     * 0  =>  id1 = id2
     * 1  =>  id1 > id2
     */
    public static int compareBundleIDs(String id1, String id2, boolean direction) {
        long l1 = getCounterFromBundleID(id1, direction);
        long l2 = getCounterFromBundleID(id2, direction);

        return Long.compareUnsigned(l1, l2);
    }

    /* Extracts Counter value from the bundle ID
     * Parameters:
     * bundleID:    Encoded BundleID
     * direction:   UPSTREAM (Client->Server), DOWNSTREAM (Server->Client)
     * Returns:
     * counter value as an unsigned long
     */
    public static long getCounterFromBundleID(String bundleID, boolean direction) {
        byte[] bundleIDBytes = Base64.getUrlDecoder().decode(bundleID);

        int index = (direction == DOWNSTREAM) ? 0 : (bundleIDBytes.length - 1);

        return Long.parseUnsignedLong(Byte.toString(bundleIDBytes[index]));
    }

    public static String getClientIDFromBundleID(String bundleID, boolean direction) {
        byte[] bundleIDBytes = Base64.getUrlDecoder().decode(bundleID);
        byte[] clientIDBytes = null;

        if (direction == UPSTREAM) {
            clientIDBytes = Arrays.copyOfRange(bundleIDBytes, 0, bundleIDBytes.length - 1);
        } else {
            clientIDBytes = Arrays.copyOfRange(bundleIDBytes, 1, bundleIDBytes.length);
        }

        return Base64.getUrlEncoder().encodeToString(clientIDBytes);
    }
}