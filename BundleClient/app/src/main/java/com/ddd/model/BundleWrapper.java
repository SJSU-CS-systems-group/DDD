package com.ddd.model;

import java.io.File;

public class BundleWrapper {
    private String bundleId;
    private EncryptionHeader encHeader;
    private EncryptedBundle encbundle;
    private File payloadSignature;
    private File source;

    public BundleWrapper(String bundleId, EncryptionHeader encHeader, EncryptedBundle encbundle, File source, File payloadSignature) {
        this.bundleId = bundleId;
        this.encHeader = encHeader;
        this.encbundle = encbundle;
        this.source = source;
        this.payloadSignature = payloadSignature;
    }

    public String getBundleId() {
        return bundleId;
    }

    public EncryptionHeader getEncHeader() {
        return encHeader;
    }

    public EncryptedBundle getEncbundle() {
        return encbundle;
    }

    public File getSource() {
        return source;
    }

    public File getPayloadSignature() {
        return payloadSignature;
    }
}
