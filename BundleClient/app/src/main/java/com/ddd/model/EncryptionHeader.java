package com.ddd.model;

import java.io.File;

public class EncryptionHeader {
    private File clientBaseKey;
    private File clientIdentityKey;
    private File payloadSignature;

    public EncryptionHeader(File clientBaseKey, File clientIdentityKey, File payloadSignature) {
        this.clientBaseKey = clientBaseKey;
        this.clientIdentityKey = clientIdentityKey;
        this.payloadSignature = payloadSignature;
    }

    public File getClientBaseKey() {
        return clientBaseKey;
    }

    public File getClientIdentityKey() {
        return clientIdentityKey;
    }

    public File getPayloadSignature() {
        return payloadSignature;
    }
}
