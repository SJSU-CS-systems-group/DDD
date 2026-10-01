package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Filesystem layout of the dropbox, rooted at the app's external files dir
 * ({@code getExternalFilesDir(null)}, falling back to {@code filesDir}).
 *
 * <pre>
 * root/
 *   Dropbox/
 *     upstream/     committed client-to-server bundles (file name = encrypted bundle id)
 *     downstream/   committed server-to-client bundles (file name = encrypted bundle id)
 *     staging/      in-flight receives, never listed or served
 *     meta/         ledgers, delivery log, recencyBlob.bin
 *   GrpcSecurity/   TLS identity, same layout as the transport ({@code GrpcSecurityKey(path, SERVER)})
 * </pre>
 *
 * <p>Deliberately separate from bundle-core's {@code TransportPaths} ({@code BundleTransmission/client}
 * and {@code /server}): those folders treat every regular file as a bundle and have no staging area.
 * {@link #grpcSecurity} keeps the transport's {@code GrpcSecurity} name so the TLS key layout is identical.
 *
 * <p>Public final fields mirror bundle-core's {@code TransportPaths} convention. Construction does not
 * touch the disk; call {@link #createDirectories()} once at startup.
 */
public final class DropboxPaths {
    /** Name of the recency blob file inside {@link #meta}. */
    public static final String RECENCY_BLOB_FILE_NAME = "recencyBlob.bin";

    /** App storage root. */
    public final Path root;
    /** {@code root/Dropbox}: everything the relay owns. */
    public final Path dropboxDir;
    /** {@code root/Dropbox/upstream}: committed {@link Direction#UPSTREAM} bundles. */
    public final Path upstream;
    /** {@code root/Dropbox/downstream}: committed {@link Direction#DOWNSTREAM} bundles. */
    public final Path downstream;
    /** {@code root/Dropbox/staging}: temp files for receives in progress. */
    public final Path staging;
    /** {@code root/Dropbox/meta}: ledgers, logs and the recency blob. */
    public final Path meta;
    /** {@code root/Dropbox/meta/recencyBlob.bin}: raw {@code GetRecencyBlobResponse} relayed from carriers. */
    public final Path recencyBlobFile;
    /** {@code root/GrpcSecurity}: TLS keys and self-signed cert, passed to {@code GrpcSecurityKey}. */
    public final Path grpcSecurity;

    /**
     * Computes all paths below {@code root}. Does no I/O.
     *
     * @param root app storage root
     */
    public DropboxPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        this.dropboxDir = root.resolve("Dropbox");
        this.upstream = dropboxDir.resolve("upstream");
        this.downstream = dropboxDir.resolve("downstream");
        this.staging = dropboxDir.resolve("staging");
        this.meta = dropboxDir.resolve("meta");
        this.recencyBlobFile = meta.resolve(RECENCY_BLOB_FILE_NAME);
        // Same directory name as the transport (SecurityUtils.GRPC_SECURITY_PATH = "GrpcSecurity").
        this.grpcSecurity = root.resolve("GrpcSecurity");
    }

    /**
     * @param d a direction
     * @return the committed-bundle directory for that direction
     */
    public Path dirFor(Direction d) {
        return d == Direction.UPSTREAM ? upstream : downstream;
    }

    /**
     * Creates every directory in the layout if missing. Idempotent.
     *
     * @return this, for chaining
     * @throws IOException if a directory cannot be created
     */
    public DropboxPaths createDirectories() throws IOException {
        Files.createDirectories(upstream);
        Files.createDirectories(downstream);
        Files.createDirectories(staging);
        Files.createDirectories(meta);
        Files.createDirectories(grpcSecurity);
        return this;
    }

    @Override
    public String toString() {
        return "DropboxPaths{root=" + root + "}";
    }
}
