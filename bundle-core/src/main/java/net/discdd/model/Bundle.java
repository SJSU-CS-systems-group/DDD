package net.discdd.model;

import java.io.File;

public class Bundle {
    private final File source;
    private final String id;

    public Bundle(String id, File source) {
        this.source = source;
        this.id = id;
    }

    public Bundle(File source) {
        this.source = source;
        this.id = null;
    }

    public String getBundleId() {
        return id;
    }

    public File getSource() {
        return this.source;
    }
}
