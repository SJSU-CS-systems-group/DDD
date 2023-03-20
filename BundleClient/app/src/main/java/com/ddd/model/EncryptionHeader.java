package com.ddd.model;

import java.io.File;

public class EncryptionHeader {
    private File clientBaseKey;
    private File clientIdentityKey;

    public EncryptionHeader(File clientBaseKey, File clientIdentityKey) {
        this.clientBaseKey = clientBaseKey;
        this.clientIdentityKey = clientIdentityKey;
    }

    public File getClientBaseKey() {
        return clientBaseKey;
    }

    public File getClientIdentityKey() {
        return clientIdentityKey;
    }
}
