package com.ddd.client.bundlerouting;

import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.client.bundlerouting.WindowUtils.CircularBuffer;

import java.nio.file.Files;
import java.nio.file.Paths;


public class ClientWindow {
    CircularBuffer window       = null;
    private int windowLength    = 10; /* Default Value */

    /* Begin and End are used as Unsigned Long */
    private long begin          = 0;
    private long end            = 0;

    /* 
     * Allocate and Initialize Window with provided size
     * Uses default size(10) if provided size is <= 0
     * Parameter:
     * size:    Size of window
     * Returns:
     * None
     */
    public ClientWindow(int length) throws InvalidLength, BufferOverflow
    {
        if (length > 0) {
            windowLength = length;
        } else {
            Log.d(HelloworldActivity.Tag, "Invalid window size, using default size [%d]", windowLength);
        }

        window = new CircularBuffer(windowLength);

        /* Initialize Slots */
        fillWindow(windowLength, begin);
    }

    public void fillWindow(int count, long startCounter) throws BufferOverflow
    {
        long length = startCounter + count;

        for (long i = startCounter; i < length; ++i) {
            window.add(generateBundleID(i));
        }

        end = begin + windowLength;
    }

    String generateBundleID(long counter)
    {
        return Long.toUnsignedString(counter);
    }

    public void processACK(String ackPath) throws IOException, RecievedOldACK, RecievedInvalidACK, InvalidLength, BufferOverflow
    {
        String ackStr = new String(Files.readAllBytes(Paths.get(ackPath)));
        Log.d(HelloworldActivity.Tag, "Ack from file = "+ackStr);
        long ack = Long.parseUnsignedLong(ackStr);

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
        Log.d(HelloworldActivity.Tag, "Updated Begin: "+Long.toUnsignedString(begin)+"; End: "+Long.toUnsignedString(end));
    }

    public String[] getWindow()
    {
        return window.getBuffer();
    }
}