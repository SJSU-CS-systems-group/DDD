package com.ddd.client.bundlerouting.WindowUtils;

import java.util.ArrayList;
import java.util.List;

import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.BufferUnderflow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;

public class CircularBuffer {
    private String[] buffer = null;
    
    private int start       = 0;
    private int end         = -1;
    private int length      = 0;
    private int capacity    = 0;

    public CircularBuffer(int length) throws InvalidLength
    {
        if (length <= 0) {
            throw new InvalidLength("Length ["+length+"] <= 0");
        }
        buffer      = new String[length];
        this.length = length;
    }

    public void add(String item) throws BufferOverflow
    {
        if (capacity + 1 > length) {
            throw new BufferOverflow("Exceeding lenght("+length+")");
        }

        end = (end + 1) % length;
        buffer[end] = item;
        capacity++;
    }

    private void delete() throws BufferUnderflow
    {
        if (capacity == 0) {
            throw new BufferUnderflow("Buffer is empty");
        }

        buffer[start] = null;
        start = (start + 1) % length;
        capacity--;
    }

    public int deleteUntilIndex(int index) throws InvalidLength
    {
        int i = start;
        int count = 1;

        if (index > length) {
            throw new InvalidLength("Invalid Index provided, length = ["+length+"]");
        }

        /* Delete elements until provided index */
        while (buffer[i] != buffer[index]) {
            try {
                delete();
                count++;
                i = (i + 1) % length;
            } catch (BufferUnderflow e) {
                // TODO Change to LOG Warn
                System.out.println("ERROR: Buffer is Empty");
            }
        }

        /* Delete the element at index */
        try {
            delete();
        } catch (BufferUnderflow e) {
            // TODO Change to LOG Warn
            System.out.println("ERROR: Buffer is Empty");
        }

        /* Return number of deleted items */
        return count;
    }

    public String[] getBuffer()
    {
        List<String> sList = new ArrayList<String>();
        int count = capacity;
        int i = start;

        while(count != 0) {
            sList.add(buffer[i]);
            i = (i + 1) % length;
            count--;
        }

        return sList.toArray(new String[sList.size()]);
    }

    public int getLength()
    {
        return this.length;
    }

    public boolean isBufferFull()
    {
        return this.capacity == 0;
    }

    public String getValueAtEnd()
    {
        return buffer[end];
    }
}