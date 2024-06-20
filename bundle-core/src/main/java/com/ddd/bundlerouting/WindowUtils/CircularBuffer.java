package com.ddd.bundlerouting.WindowUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.ddd.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.BufferUnderflow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;

import static java.util.logging.Level.WARNING;

public class CircularBuffer {
    private static final Logger logger = Logger.getLogger(CircularBuffer.class.getName());

    private String[] buffer = null;

    private int start = 0;
    private int end = -1;
    private int length = 0;
    private int capacity = 0;

    public CircularBuffer(int length) throws InvalidLength {
        if (length <= 0) {
            throw new InvalidLength("Length [" + length + "] <= 0");
        }
        buffer = new String[length];
        this.length = length;
    }

    public void add(String item) throws BufferOverflow {
        if (capacity + 1 > length) {
            throw new BufferOverflow("Exceeding lenght(" + length + ")");
        }

        end = (end + 1) % length;
        buffer[end] = item;
        capacity++;
    }

    // Used only to initialize window to an older state
    public void initializeFromIndex(String item, int index) throws BufferOverflow {
        if (index >= length) {
            throw new BufferOverflow("Exceeding lenght(" + length + ")");
        }

        buffer[index] = item;
        start = end = index;
        capacity = 1;
    }

    private void delete() throws BufferUnderflow {
        if (capacity == 0) {
            throw new BufferUnderflow("Buffer is empty");
        }

        buffer[start] = null;
        start = (start + 1) % length;
        capacity--;
    }

    public int deleteUntilIndex(int index) throws InvalidLength {
        int i = start;
        int count = 1;

        if (index > length) {
            throw new InvalidLength("Invalid Index provided, length = [" + length + "]");
        }

        /* Delete elements until provided index */
        while (buffer[i] != buffer[index]) {
            try {
                delete();
                count++;
                i = (i + 1) % length;
            } catch (BufferUnderflow e) {
                // TODO Change to LOG Warn
                logger.log(WARNING, "ERROR: Buffer is Empty");
            }
        }

        /* Delete the element at index */
        try {
            delete();
        } catch (BufferUnderflow e) {
            // TODO Change to LOG Warn
            logger.log(WARNING, "ERROR: Buffer is Empty");
        }

        /* Return number of deleted items */
        return count;
    }

    public List<String> getBuffer() {
        List<String> sList = new ArrayList<String>();
        int count = capacity;
        int i = start;

        while (count != 0) {
            sList.add(buffer[i]);
            i = (i + 1) % length;
            count--;
        }

        return sList;
    }

    public int getLength() {
        return this.length;
    }

    public long getStart() {
        return this.start;
    }

    public long getEnd() {
        return this.end;
    }

    public boolean isBufferFull() {
        return this.capacity == this.length;
    }

    public String getValueAtEnd() {
        return buffer[end];
    }
}