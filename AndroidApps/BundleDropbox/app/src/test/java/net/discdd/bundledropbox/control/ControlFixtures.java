package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Shared helpers for the control-package tests: a real {@link FileRelayStore} and bundle-shaped jars. */
final class ControlFixtures {
    static final long CAPACITY = 10L * 1024 * 1024;

    private ControlFixtures() {}

    /** A real relay store under {@code root} with a deterministic clock. */
    static FileRelayStore newStore(Path root, AtomicLong clock) throws IOException {
        return new FileRelayStore(new DropboxPaths(root).createDirectories(), CAPACITY, () -> clock.addAndGet(10));
    }

    /**
     * Writes a jar with the entries the dropbox reads from a real bundle: {@code bundle.id}, an optional
     * {@code clientBase.pub} in {@code DDDPEMEncoder} form, and an opaque payload.
     *
     * @param clientBase the client's base key, or {@code null} for a jar without one
     */
    static void writeJar(OutputStream out, String bundleId, int payloadBytes, ECPublicKey clientBase)
            throws IOException {
        try (JarOutputStream jar = new JarOutputStream(out)) {
            if (clientBase != null) {
                jar.putNextEntry(new JarEntry("clientBase.pub"));
                jar.write(DDDPEMEncoder.createEncodedPublicKeyBytes(clientBase));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("bundle.id"));
            jar.write(bundleId.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("payloads/payload"));
            jar.write(new byte[payloadBytes]);
            jar.closeEntry();
        }
    }

    /** Commits a bundle jar into {@code store} and returns its record. */
    static BundleRecord commit(FileRelayStore store, Direction d, String bundleId, int payloadBytes,
                               ECPublicKey clientBase) throws IOException {
        Staged s = store.beginStaging(d);
        try (OutputStream out = s.open()) {
            writeJar(out, bundleId, payloadBytes, clientBase);
        }
        return store.commit(s, null);
    }

    /** Writes a jar file (not committed anywhere). */
    static Path jarFile(Path dir, String bundleId, int payloadBytes, ECPublicKey clientBase) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(bundleId);
        try (OutputStream out = Files.newOutputStream(p)) {
            writeJar(out, bundleId, payloadBytes, clientBase);
        }
        return p;
    }
}
