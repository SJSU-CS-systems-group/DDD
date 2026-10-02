package net.discdd.bundledropbox.visit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds minimal bundle jars for tests: a {@code bundle.id} entry (the real layout written by
 * {@code BundleUtils.encryptPayloadAndCreateBundle}) plus an opaque {@code payloads/payload} of random bytes.
 * The relay never looks inside a bundle beyond {@code bundle.id}, so this is all it needs.
 */
final class BundleFixtures {
    private BundleFixtures() {}

    /**
     * @param dir          directory to create the file in
     * @param fileName     file name (on a carrier this must equal the bundle id)
     * @param bundleId     content of the {@code bundle.id} entry
     * @param payloadBytes size of the random payload
     * @return the created jar
     */
    static Path bundleJar(Path dir, String fileName, String bundleId, int payloadBytes) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve(fileName);
        byte[] payload = new byte[payloadBytes];
        new Random(bundleId.hashCode()).nextBytes(payload);
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("bundle.id"));
            zip.write(bundleId.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payloads/payload"));
            zip.write(payload);
            zip.closeEntry();
        }
        return jar;
    }
}
