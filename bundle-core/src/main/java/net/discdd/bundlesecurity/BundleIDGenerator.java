package net.discdd.bundlesecurity;

import com.google.common.primitives.Bytes;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;

public class BundleIDGenerator {
    public static final boolean UPSTREAM = true;
    public static final boolean DOWNSTREAM = false;
    /* Length of the Counter */
    private static final int COUNTER_BYTE_SIZE = Long.BYTES;

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
        byte[] bCounter = new byte[COUNTER_BYTE_SIZE];
        ByteBuffer.wrap(bCounter).putLong(counter);
        bCounter[0] = (byte) counter;

        byte[] bundleID = direction == UPSTREAM ? Bytes.concat(bClientID, bCounter) : Bytes.concat(bCounter, bClientID);

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

        int index = (direction == DOWNSTREAM) ? 0 : (bundleIDBytes.length - COUNTER_BYTE_SIZE);

        return ByteBuffer.wrap(bundleIDBytes, index, COUNTER_BYTE_SIZE).getLong();
    }

    public static String getClientIDFromBundleID(String bundleID, boolean direction) {
        byte[] bundleIDBytes = Base64.getUrlDecoder().decode(bundleID);
        byte[] clientIDBytes = null;

        if (direction == UPSTREAM) {
            clientIDBytes = Arrays.copyOfRange(bundleIDBytes, 0, bundleIDBytes.length - COUNTER_BYTE_SIZE);
        } else {
            clientIDBytes = Arrays.copyOfRange(bundleIDBytes, COUNTER_BYTE_SIZE, bundleIDBytes.length);
        }

        return Base64.getUrlEncoder().encodeToString(clientIDBytes);
    }
}