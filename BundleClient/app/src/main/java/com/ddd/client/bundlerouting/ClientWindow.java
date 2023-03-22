package ddd.client.bundlerouting;

import ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import ddd.client.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import ddd.client.bundlerouting.WindowUtils.CircularBuffer;

import java.nio.file.Files;
import java.nio.file.Paths;


public class ClientWindow {
    CircularBuffer window       = null;
    private int windowLength    = 10; /* Default Value */

    /* Begin and End are used as Unsigned Long */
    private long begin          = 1;
    private long end            = windowLength;

        /* 
     * Allocate and Initialize Window with provided size
     * Uses default size(10) if provided size is <= 0
     * Parameter:
     * size:    Size of window
     * Returns:
     * None
     */
    ClientWindow(int length) throws InvalidLength, BufferOverflow
    {
        if (length > 0) {
            windowLength = length;
        } else {
            //TODO: Change to log
            System.out.printf("Invalid window size, using default size [%d]", windowLength);
        }

        window = new CircularBuffer(windowLength);

        updateSlots(begin, windowLength);
    }

    String generateBundleID(long counter)
    {
        return Long.toUnsignedString(counter);
    }

    private void updateSlots(long begin, long length) throws BufferOverflow
    {
        for (long i = begin; i < length; ++i) {
            window.add(generateBundleID(i));
        }
    }

    public String[] getWindow()
    {
        return window.getBuffer();
    }
}