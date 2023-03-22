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
        end         = length - 1;
        this.length = length;
    }

    public void add(String item) throws BufferOverflow
    {
        if (capacity + 1 > length) {
            throw new BufferOverflow("Exceeding lenght["+length+"]");
        }

        end = (end + 1) % length;
        buffer[end] = item;
        capacity++;
    }

    public void delete() throws BufferUnderflow
    {
        if (capacity == 0) {
            throw new BufferUnderflow("Buffer is empty");
        }

        buffer[start] = null;
        start = (start + 1) % length;
        capacity--;
    }

    public String[] getBuffer()
    {
        List<String> sList = new ArrayList<String>();
        int count = capacity;
        int i = start;

        System.out.println("Capacity = "+capacity);
        while(count != 0) {
            sList.add(buffer[i]);
            i = (i + 1) % length;
            count--;
        }

        return sList.toArray(new String[sList.size()]);
    }
}