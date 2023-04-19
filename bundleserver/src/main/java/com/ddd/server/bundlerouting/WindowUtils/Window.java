package com.ddd.server.bundlerouting.WindowUtils;

import com.ddd.server.bundlesecurity.BundleIDGenerator;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidBundleID;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;

public class Window {
    private final int DefaultwindowLength   = 10; /* Default Value */
    private CircularBuffer circularBuffer   = null;

    /* Counters are used to maintain range in the window
     * and are used as Unsigned Long */
    private long startCounter       = 0;
    private long endCounter         = 0;

    public Window(int length) throws InvalidLength
    {
        if (length < 1) {
            length = DefaultwindowLength;
            //TODO: Change to log
            System.out.printf("Invalid window size, using default size [%d]", DefaultwindowLength);
        }

        circularBuffer = new CircularBuffer(length);
    }

    public void add(String bundleID) throws BufferOverflow, InvalidBundleID
    {
        long bundleIDcounter = BundleIDGenerator.getCounterFromBundleID(bundleID, BundleIDGenerator.DOWNSTREAM);
        
        if (endCounter != bundleIDcounter) {
            throw new InvalidBundleID("Expected: "+Long.toUnsignedString(endCounter)+", Got: "+Long.toUnsignedString(bundleIDcounter));
        }

        circularBuffer.add(bundleID);
        endCounter++;
    }

    public String getCurrentbundleID(String clientID)
    {
        return BundleIDGenerator.generateBundleID(clientID, endCounter, BundleIDGenerator.DOWNSTREAM);
    }

    public String getLatestBundleID()
    {
        return circularBuffer.getValueAtEnd();
    }

    public void moveWindowAhead(long ack) throws InvalidLength, RecievedOldACK, RecievedInvalidACK
    {
        compareBundleID(ack);
        
        int index = (int) Long.remainderUnsigned(ack, circularBuffer.getLength());
        circularBuffer.deleteUntilIndex(index);
        startCounter = ack + 1;
        // TODO: Change to log
        System.out.println("Updated start: "+startCounter+"; End: "+(endCounter+1));
    }

    public void compareBundleID(long ack) throws RecievedOldACK, RecievedInvalidACK
    {
        if (Long.compareUnsigned(ack,startCounter) == -1) {
            throw new RecievedOldACK("Received old ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(startCounter) + "]" );
        } else if (Long.compareUnsigned(ack,endCounter) == 1) {
            throw new RecievedInvalidACK("Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long.toUnsignedString(endCounter) + "]" );
        }
    }

    public String[] getWindow()
    {
        return circularBuffer.getBuffer();
    }

    public boolean isWindowFull()
    {
        return circularBuffer.isBufferFull();
    }
}
