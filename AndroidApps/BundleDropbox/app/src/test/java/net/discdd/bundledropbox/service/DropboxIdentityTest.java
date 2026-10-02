package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.store.DropboxPaths;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link DropboxIdentity}: transport-compatible baseId, persisted generation, and the
 * rotate setting (03 Architecture §5).
 */
public class DropboxIdentityTest {
    /**
     * Pure-Java stand-in for Android {@code Base64.encodeToString(b, Base64.DEFAULT)}: 76-char lines joined
     * by '\n' and a trailing '\n' (the transport's {@code len-20 .. len-4} offsets depend on that newline).
     */
    static final Function<byte[], String> ANDROID_DEFAULT_BASE64 =
            b -> Base64.getMimeEncoder(76, new byte[] { '\n' }).encodeToString(b) + "\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxPaths paths() throws Exception {
        return new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
    }

    @Test
    public void baseIdUsesTheTransportFormula() throws Exception {
        DropboxIdentity id = DropboxIdentity.load(paths(), ANDROID_DEFAULT_BASE64, true);
        String full = ANDROID_DEFAULT_BASE64.apply(id.keys().grpcKeyPair.getPublic().getEncoded());
        assertEquals(full.substring(full.length() - 20, full.length() - 4), id.baseId());
        assertEquals(16, id.baseId().length());
        assertTrue("no line breaks inside the id", id.baseId().indexOf('\n') < 0);
    }

    @Test
    public void keysAndBaseIdAreStableAcrossRestarts() throws Exception {
        DropboxPaths p = paths();
        DropboxIdentity first = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64, true);
        DropboxIdentity second = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64, true);
        assertEquals(first.baseId(), second.baseId());
    }

    @Test
    public void generationRotatesAndPersists() throws Exception {
        DropboxPaths p = paths();
        DropboxIdentity id = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64, true);
        assertEquals(id.baseId() + "-0", id.advertisedId());
        String next = id.bumpGeneration();
        assertEquals(id.baseId() + "-1", next);
        assertEquals(next, id.advertisedId());
        assertEquals("1", new String(Files.readAllBytes(p.meta.resolve(DropboxIdentity.GENERATION_FILE_NAME)),
                                     StandardCharsets.UTF_8).trim());

        DropboxIdentity restarted = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64, true);
        assertEquals("an id is never reused after a restart", next, restarted.advertisedId());
    }

    @Test
    public void rotationOffAdvertisesPlainBaseId() throws Exception {
        DropboxIdentity id = DropboxIdentity.load(paths(), ANDROID_DEFAULT_BASE64, false);
        id.bumpGeneration();
        assertEquals(id.baseId(), id.advertisedId());
        id.setRotate(true);
        assertEquals(id.baseId() + "-1", id.advertisedId());
    }

    @Test
    public void corruptGenerationFileRestartsAtZero() throws Exception {
        DropboxPaths p = paths();
        Files.write(p.meta.resolve(DropboxIdentity.GENERATION_FILE_NAME), "garbage".getBytes(StandardCharsets.UTF_8));
        DropboxIdentity id = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64, true);
        assertEquals(0, id.generation());
        assertNotEquals(id.baseId(), id.advertisedId());
    }
}
