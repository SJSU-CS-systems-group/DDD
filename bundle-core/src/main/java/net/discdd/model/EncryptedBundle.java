package net.discdd.model;

import java.io.File;

public class EncryptedBundle {
    private File source;

    public EncryptedBundle(File source) {
        this.source = source;
    }

    public File getSource() { return source; }
}
