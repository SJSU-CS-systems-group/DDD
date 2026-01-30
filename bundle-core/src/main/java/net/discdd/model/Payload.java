package net.discdd.model;

import java.io.File;

public class Payload {
    private final String bundleId;
    private final File source;

    public Payload(String bundleId, File source) {
        this.bundleId = bundleId;
        this.source = source;
    }

    public String getBundleId() { return this.bundleId; }

    public File getSource() { return this.source; }
}
