package net.discdd.bundledropbox.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** JVM tests for {@link BundleIds}: reading {@code bundle.id} from generated jars and id validation. */
public class BundleIdsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jar(String idEntry) throws IOException {
        Path p = tmp.newFile().toPath();
        try (OutputStream out = Files.newOutputStream(p)) {
            TestBundles.writeJar(out, idEntry, 64);
        }
        return p;
    }

    @Test
    public void readsIdFromJar() throws IOException {
        assertEquals("abc-DEF_123==", BundleIds.read(jar("abc-DEF_123==")));
    }

    @Test
    public void trimsWhitespaceLikeBundleUtils() throws IOException {
        assertEquals("abc", BundleIds.read(jar("  abc\n")));
    }

    @Test
    public void missingEntryFails() throws IOException {
        Path p = jar(null);
        assertFalse(BundleIds.tryReadRaw(p).isPresent());
        try {
            BundleIds.read(p);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void notAZipFails() throws IOException {
        Path p = tmp.newFile().toPath();
        Files.write(p, new byte[]{1, 2, 3, 4});
        assertFalse(BundleIds.tryReadRaw(p).isPresent());
        try {
            BundleIds.read(p);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void uncleanIdInJarFails() throws IOException {
        Path p = jar("../../evil");
        assertEquals("../../evil", BundleIds.tryReadRaw(p).get());   // raw read succeeds...
        try {
            BundleIds.read(p);                                        // ...validated read refuses it
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void cleanCheckMatchesBundleUtils() {
        assertTrue(BundleIds.isClean("AZaz09-_="));
        assertFalse(BundleIds.isClean(null));
        assertFalse(BundleIds.isClean(""));
        assertFalse(BundleIds.isClean("a/b"));
        assertFalse(BundleIds.isClean("a.b"));
        assertFalse(BundleIds.isClean("x".repeat(101)));
        assertTrue(BundleIds.isClean("x".repeat(100)));
    }
}
