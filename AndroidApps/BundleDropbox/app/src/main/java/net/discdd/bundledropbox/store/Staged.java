package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * A bundle being received, before it becomes visible in the {@link RelayStore}.
 *
 * <p>Created by {@link RelayStore#beginStaging(Direction)}. The writer streams bytes into
 * {@link #open()}, closes the stream, and then calls exactly one of
 * {@link RelayStore#commit(Staged, String)} or {@link RelayStore#abort(Staged)}.
 *
 * <p>Why staging exists: the transport writes uploads straight to their final name, so a cancelled
 * or partial upload leaves a truncated file that would later be forwarded (Research/R1 §3.2). The
 * dropbox instead writes to {@code staging/} and only atomically moves complete files into
 * {@code upstream/} or {@code downstream/}. This is also what makes "drain cap reached, cancel and
 * keep the partial work retryable" safe: an aborted staged file is simply discarded and the peer
 * re-sends it next time.
 */
public interface Staged {
    /** @return the temporary file under {@link DropboxPaths#staging}; never visible to PSI or forwarding. */
    Path path();

    /**
     * Opens (create/truncate) the staged file for writing. The caller owns and must close the stream
     * before committing. Calling it more than once truncates and restarts the file.
     *
     * @throws IOException if the file cannot be opened
     */
    OutputStream open() throws IOException;
}
