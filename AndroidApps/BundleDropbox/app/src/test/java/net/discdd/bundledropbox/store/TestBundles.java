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
        try (JarOutputStream jar = new JarOutputStream(out)) {
            if (idEntryContent != null) {
                jar.putNextEntry(new JarEntry(BundleIds.BUNDLE_ID_ENTRY));
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
