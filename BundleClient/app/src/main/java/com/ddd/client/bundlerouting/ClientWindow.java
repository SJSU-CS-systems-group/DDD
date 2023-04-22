package com.ddd.client.bundlerouting;

import static com.ddd.bundleclient.HelloworldActivity.TAG;

import android.util.Log;

import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;

import org.whispersystems.libsignal.InvalidKeyException;

import com.ddd.client.bundlerouting.WindowUtils.CircularBuffer;
import com.ddd.client.bundlesecurity.BundleIDGenerator;
import com.ddd.client.bundlesecurity.ClientSecurity;
import com.ddd.client.bundlesecurity.SecurityExceptions.AESAlgorithmException;
import com.ddd.client.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;

import java.util.List;

public class ClientWindow {
    static private ClientWindow singleClientWindowInstance = null;
    private CircularBuffer window   = null;
    private String clientID         = null;
    private int windowLength        = 10; /* Default Value */

    /* Begin and End are used as Unsigned Long */
    private long begin          = 0;
    private long end            = 0;


    /* Generates bundleIDs for window slots
     * Parameter:
     * count            : Number of slots to be filled
     * startCounter     : counter value to begin generating new bundleIDs
     * Returns:
     * None
     */
    private void fillWindow(int count, long startCounter) throws BufferOverflow
    {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            window.add(BundleIDGenerator.generateBundleID(this.clientID, i, BundleIDGenerator.DOWNSTREAM));
        }

        end = begin + windowLength;
    }

    /* Allocate and Initialize Window with provided size
     * Uses default size(10) if provided size is <= 0
     * Parameter:
     * size:    Size of window
     * Returns:
     * None
     */
    private ClientWindow(int length, String clientID) throws InvalidLength, BufferOverflow
    {
        if (length > 0) {
            windowLength = length;
        } else {
            //TODO: Change to log
            System.out.printf("Invalid window size, using default size [%d]", windowLength);
        }

        this.clientID = clientID;
        window = new CircularBuffer(windowLength);

        /* Initialize Slots */
        fillWindow(windowLength, begin);
    }

    public static ClientWindow getInstance(int windowLength, String clientID) throws InvalidLength, BufferOverflow
    {
        if (singleClientWindowInstance == null) {
            singleClientWindowInstance = new ClientWindow(windowLength, clientID);
        }
        return singleClientWindowInstance;
    }

    /* Updates the window based on the Received bundleID
     * Parameters:
     * bundleID    : BundleID (encrypted)
     * Returns:
     * None
     */
    public void processBundle(String bundleID, ClientSecurity clientSecurity) throws BufferOverflow, BundleIDCryptographyException
    {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        System.out.println("Largest Bundle ID = "+decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        if (Long.compareUnsigned(ack,begin) == -1) {
            System.out.println("Received old [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(begin) + "]" );
            return;
        } else if (Long.compareUnsigned(ack,end) == 1) {
            System.out.println("Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(end) + "]" );
            return;
        }

        /* Index will be an int as windowLength is int */
        int ackIndex = (int) Long.remainderUnsigned(ack, windowLength);

        /* Delete ACKs until ackIndex */
        int noDeleted = 0;
        try {
            noDeleted = window.deleteUntilIndex(ackIndex);
        } catch (InvalidLength e) {
            System.out.println("Received Invalid ACK [" + Long.toUnsignedString(ack) +"]" );
        }

        begin = ack + 1;
        /* Add new bundleIDs to window */
        fillWindow(noDeleted, end);
        // TODO: Change to log
        System.out.println("Updated Begin: "+Long.toUnsignedString(begin)+"; End: "+Long.toUnsignedString(end));
    }

    /* Returns the entire window
     * Parameters:
     * None
     * Returns:
     * None
     */
    public List<String> getWindow(ClientSecurity client) throws BundleIDCryptographyException
    {
        List<String> bundleIDs = window.getBuffer();

        for (int i = 0; i < bundleIDs.size(); ++i) {
            String bundleID = client.encryptBundleID(bundleIDs.get(i));
            bundleIDs.set(i, bundleID);
        }
        return bundleIDs;
    }

    public static ClientWindow getInstance() {
      // TODO Auto-generated method stub
      return null;
    }
}