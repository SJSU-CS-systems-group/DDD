package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test helper that writes minimal bundle-shaped jars: a {@code bundle.id} entry plus an opaque
 * {@code payloads/payload}, the two entries {@code BundleUtils.encryptPayloadAndCreateBundle} writes
 * that matter to the dropbox (Research/R2 §4.2). Contents are fake; the store never decrypts.
 */
final class TestBundles {
    private TestBundles() {}

    /** Writes a jar whose {@code bundle.id} entry holds {@code idEntryContent} (null = no entry). */
    static void writeJar(OutputStream out, String idEntryContent, int payloadBytes) throws IOException {
        writeJar(out, idEntryContent, payloadBytes, null, -1);
    }

    /**
     * Like {@link #writeJar(OutputStream, String, int)} plus a {@code clientBase.pub} entry in the PEM-style
     * form {@code DDDPEMEncoder.createEncodedPublicKeyBytes} writes, and an explicit {@code bundle.id} entry
     * time (the jar build time the store records).
     *
     * @param clientKey   fake Base64url key line for {@code clientBase.pub}, or null for no entry
     * @param builtAtMs   {@code bundle.id} entry time, or -1 to let the zip writer stamp "now"
     */
    static void writeJar(OutputStream out, String idEntryContent, int payloadBytes, String clientKey, long builtAtMs)
            throws IOException {
        try (JarOutputStream jar = new JarOutputStream(out)) {
            if (clientKey != null) {
                jar.putNextEntry(new JarEntry(BundleIds.CLIENT_BASE_KEY_ENTRY));
                jar.write(("-----BEGIN EC PUBLIC KEY-----\n" + clientKey + "\n-----END EC PUBLIC KEY-----")
                                  .getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            if (idEntryContent != null) {
                JarEntry idEntry = new JarEntry(BundleIds.BUNDLE_ID_ENTRY);
                if (builtAtMs >= 0) idEntry.setTime(builtAtMs);
                jar.putNextEntry(idEntry);
                jar.write(idEntryContent.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("payloads/payload"));
            byte[] payload = new byte[payloadBytes];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
            jar.write(payload);
            jar.closeEntry();
        }
    }
}
