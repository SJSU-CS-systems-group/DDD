package net.discdd.bundlerouting.service;

import com.google.protobuf.ByteString;

public interface StreamCallback {
    void write(ByteString bytes);
}
