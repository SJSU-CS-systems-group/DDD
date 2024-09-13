package net.discdd.utils;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class DDDJarFileCreator {
    private static final String SHA256_ATTRIBUTE_NAME = "SHA-256-Digest";
    final private JarOutputStream jarOutputStream;
    final private Manifest manifest = new Manifest();
    final private HashMap<String, MessageDigest> digestOutputStreams = new HashMap<>();
    private DigestOutputStream previousDigestStream = null;

    public DDDJarFileCreator(OutputStream os) throws IOException {
        jarOutputStream = new JarOutputStream(os);
    }

    public OutputStream createEntry(String name) throws IOException, NoSuchAlgorithmException {
        name = name.replace('\\', '/');
        jarOutputStream.putNextEntry(new JarEntry(name));
        if (previousDigestStream != null) previousDigestStream.flush();
        var digest = MessageDigest.getInstance("SHA-256");
        var digestOutputStream = new DigestOutputStream(jarOutputStream, digest);
        digestOutputStreams.put(name, digest);
        previousDigestStream = digestOutputStream;
        return new UncloseableOutputStream(digestOutputStream);
    }

    public OutputStream createEntry(Path path) throws IOException, NoSuchAlgorithmException {
        return createEntry(path.toString());
    }

    public void createEntry(String name, byte[] bytes) throws IOException, NoSuchAlgorithmException {
        name = name.replace('\\', '/');
        try (var os = createEntry(name)) {
            os.write(bytes);
        }
    }

    public void createEntry(Path path, byte[] bytes) throws IOException, NoSuchAlgorithmException {
        createEntry(path.toString(), bytes);
    }

    public void close() throws IOException {
        if (previousDigestStream != null) previousDigestStream.flush();
        for (var entry : digestOutputStreams.entrySet()) {
            var digest = entry.getValue();
            var name = entry.getKey();
            var attributes = new Attributes();
            attributes.putValue(SHA256_ATTRIBUTE_NAME, Base64.getEncoder().encodeToString(digest.digest()));
            manifest.getEntries().put(name, attributes);
        }
        jarOutputStream.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
        manifest.write(jarOutputStream);
        jarOutputStream.close();
    }

    static private class UncloseableOutputStream extends FilterOutputStream {
        public UncloseableOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            // we don't want to close, but we would like to flush
            flush();
        }
    }
}
