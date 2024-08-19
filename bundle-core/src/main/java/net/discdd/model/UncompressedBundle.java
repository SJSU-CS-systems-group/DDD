package net.discdd.model;

import java.io.File;

public class UncompressedBundle {

    private final String bundleId;
    private final File source;

    private final EncryptionHeader encryptionHeader;
    private final EncryptedPayload encryptedPayload;

    public UncompressedBundle(String bundleId, File source, EncryptionHeader encryptionHeader,
                              EncryptedPayload encryptedPayload) {
        this.bundleId = bundleId;
        this.source = source;
        this.encryptionHeader = encryptionHeader;
        this.encryptedPayload = encryptedPayload;
    }

    public String getBundleId() {
        return this.bundleId;
    }

    public File getSource() {
        return this.source;
    }

    public EncryptionHeader getEncryptionHeader() {
        return this.encryptionHeader;
    }

    public EncryptedPayload getEncryptedPayload() {
        return this.encryptedPayload;
    }
}
