package net.discdd.bundlerouting.WindowUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class CircularBuffer {
    private static final Logger logger = Logger.getLogger(CircularBuffer.class.getName());

    private String[] buffer = null;

    private int start = 0;
    private int end = -1;
    private int length = 0;
    private int capacity = 0;

    public CircularBuffer(int length) {
        buffer = new String[length];
        this.capacity = length;
    }

    public void add(String item) throws WindowExceptions.BufferOverflow {
        if (length > capacity) {
            throw new WindowExceptions.BufferOverflow(String.format("Exceeding length %d > %d", length, capacity));
        }

        end = (end + 1) % capacity;
        buffer[end] = item;
        length++;
    }

    // Used only to initialize window to an older state
    public void initializeFromIndex(String item, int index) throws WindowExceptions.BufferOverflow {
        buffer[index] = item;
        start = end = index;
        length = 1;
    }

    private void delete() throws WindowExceptions.BufferUnderflow {
        if (length == 0) {
            throw new WindowExceptions.BufferUnderflow("Buffer is empty");
        }

        buffer[start] = null;
        start = (start + 1) % length;
        length--;
    }

    public int deleteUntilIndex(int index) {
        int count = 0;

        /* Delete elements until provided index */
        for (int i = start; i <= index; i++) {
            try {
                delete();
                count++;
                i = (i + 1) % capacity;
            } catch (WindowExceptions.BufferUnderflow e) {
                // TODO Change to LOG Warn
                logger.log(WARNING, "ERROR: Buffer is Empty");
            }
        }

        /* Return number of deleted items */
        return count;
    }

    public List<String> getBuffer() {
        List<String> sList = new ArrayList<String>();
        int count = length;
        int i = start;

        while (count != 0) {
            sList.add(buffer[i]);
            i = (i + 1) % capacity;
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