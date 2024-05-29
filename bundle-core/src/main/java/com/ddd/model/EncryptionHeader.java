package com.ddd.model;

import java.io.File;

public class EncryptionHeader {
    private final File serverSignedPreKey;
    private final File serverIdentityKey;
    private final File serverRatchetKey;

    public File getServerSignedPreKey() {
        return this.serverSignedPreKey;
    }

    public File getServerIdentityKey() {
        return this.serverIdentityKey;
    }

    public File getServerRatchetKey() {
        return this.serverRatchetKey;
    }

    public EncryptionHeader(File serverSignedPreKey, File serverIdentityKey, File serverRatchetKey) {
        this.serverSignedPreKey = serverSignedPreKey;
        this.serverIdentityKey = serverIdentityKey;
        this.serverRatchetKey = serverRatchetKey;
    }

    private File clientBaseKey;
    private File clientIdentityKey;

    public EncryptionHeader(File clientBaseKey, File clientIdentityKey) {
        this.clientBaseKey = clientBaseKey;
        this.clientIdentityKey = clientIdentityKey;
        this.serverSignedPreKey = null;
        this.serverIdentityKey = null;
        this.serverRatchetKey = null;
    }

    public File getClientBaseKey() {
        return clientBaseKey;
    }

    public File getClientIdentityKey() {
        return clientIdentityKey;
    }
}
