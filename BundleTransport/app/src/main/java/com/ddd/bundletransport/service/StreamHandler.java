package com.ddd.bundletransport.service;

import com.google.protobuf.ByteString;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

public class StreamHandler {
    private final static int BUFFER_SIZE = 4096;
    private final InputStream in;
    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;

    public StreamHandler(InputStream in) {
        this.in = in;
        this.channel = Channels.newChannel(in);
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    public Exception handle(StreamCallback callback) {
        assert callback != null;
        try {
            while (true) {
                int n = channel.read(buffer);
                if (n <= 0) break;
                buffer.flip();
                callback.write(ByteString.copyFrom(buffer));
                buffer.clear();
            }
            in.close();
            channel.close();
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }
}
