package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundlesecurity.SecurityUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link DropboxIdentity}: transport-compatible baseId, which is also the advertised id (no
 * rotation, 09 Own Recency Blob Design), and the own sender id the server mints recency blobs for.
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
        DropboxIdentity id = DropboxIdentity.load(paths(), ANDROID_DEFAULT_BASE64);
        String full = ANDROID_DEFAULT_BASE64.apply(id.keys().grpcKeyPair.getPublic().getEncoded());
        assertEquals(full.substring(full.length() - 20, full.length() - 4), id.baseId());
        assertEquals(16, id.baseId().length());
        assertTrue("no line breaks inside the id", id.baseId().indexOf('\n') < 0);
    }

    @Test
    public void keysAndIdsAreStableAcrossRestarts() throws Exception {
        DropboxPaths p = paths();
        DropboxIdentity first = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64);
        DropboxIdentity second = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64);
        assertEquals(first.baseId(), second.baseId());
        assertEquals(first.ownSenderId(), second.ownSenderId());
    }

    /** 09 Own Recency Blob Design: the advertised transportId is the stable baseId; there is no rotation. */
    @Test
    public void advertisedIdIsAlwaysTheBaseId() throws Exception {
        DropboxPaths p = paths();
        DropboxIdentity id = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64);
        assertEquals(id.baseId(), id.advertisedId());
        assertEquals(id.baseId(), DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64).advertisedId());
    }

    /** A counter file left by a build with advertised-id rotation is removed and changes nothing. */
    @Test
    public void leftoverGenerationFileIsDeletedHarmlessly() throws Exception {
        DropboxPaths p = paths();
        Path leftover = p.meta.resolve(DropboxIdentity.LEGACY_GENERATION_FILE_NAME);
        Files.write(leftover, "7".getBytes(StandardCharsets.UTF_8));
        DropboxIdentity id = DropboxIdentity.load(p, ANDROID_DEFAULT_BASE64);
        assertFalse(Files.exists(leftover));
        assertEquals(id.baseId(), id.advertisedId());
    }

    /**
     * The own sender id is what the server's gRPC getRecencyBlob computes for a peer presenting this TLS key
     * ({@code SecurityUtils.generateID(cert.getPublicKey().getEncoded())}), so a blob minted for it is the
     * dropbox's own.
     */
    @Test
    public void ownSenderIdMatchesTheServersFormulaForTheTlsKey() throws Exception {
        DropboxIdentity id = DropboxIdentity.load(paths(), ANDROID_DEFAULT_BASE64);
        assertEquals(SecurityUtils.generateID(id.keys().grpcCert.getPublicKey().getEncoded()), id.ownSenderId());
        assertSame(id.keys().grpcKeyPair, id.tlsKeyPair());
    }
}
