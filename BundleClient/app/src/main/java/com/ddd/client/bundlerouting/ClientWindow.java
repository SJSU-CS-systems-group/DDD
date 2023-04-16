package com.ddd.client.bundlerouting;

import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import com.ddd.client.bundlerouting.WindowUtils.CircularBuffer;
import com.ddd.client.bundlesecurity.BundleIDGenerator.BundleID;

public class ClientWindow {
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
    private void fillWindow(int count, long startCounter) throws BufferOverflow, org.whispersystems.libsignal.InvalidKeyException
    {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            window.add(BundleID.generateBundleID(this.clientID, i, BundleID.DOWNSTREAM));
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
    public ClientWindow(int length, String clientID) throws InvalidLength, BufferOverflow, org.whispersystems.libsignal.InvalidKeyException
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

    /* Updates the window based on the Received bundleID
     * Parameters:
     * bundleID    : BundleID (dencrypted)
     * Returns:
     * None
     */
    public void processBundle(String bundleID) throws IOException, RecievedOldACK, RecievedInvalidACK, InvalidLength, BufferOverflow, org.whispersystems.libsignal.InvalidKeyException
    {

        System.out.println("Largest Bundle ID = "+bundleID);
        long ack = BundleID.getCounterFromBundleID(bundleID, BundleID.DOWNSTREAM);

        if (Long.compareUnsigned(ack,begin) == -1) {
            throw new RecievedOldACK("Received old ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(begin) + "]" );
        } else if (Long.compareUnsigned(ack,end) == 1) {
            throw new RecievedInvalidACK("Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(end) + "]" );
        }

        /* Index will be an int as windowLength is int */
        int ackIndex = (int) Long.remainderUnsigned(ack, windowLength);

        /* Delete ACKs until ackIndex */
        int noDeleted = window.deleteUntilIndex(ackIndex);

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
    public String[] getWindow(ClientSecurity client) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, org.whispersystems.libsignal.InvalidKeyException
    {
        String[] bundleIDs = window.getBuffer();

        for (int i = 0; i < bundleIDs.length; ++i) {
            bundleIDs[i] = client.encryptBundleID(bundleIDs[i]);
        }
        
        return bundleIDs;
    }
}
