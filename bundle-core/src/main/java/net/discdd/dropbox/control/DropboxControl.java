package net.discdd.dropbox.control;

/**
 * Constants of the dropbox control channel: the ADU app ID, the wire version, the message type
 * discriminators, size bounds, and the reason vocabularies shared by the server and the BundleDropbox app.
 *
 * <p>The channel works like BundleClient's server messages (Research/R6): the dropbox runs its own DDD client
 * endpoint (Research/R7) and sends {@link InventoryReport} ADUs under {@link #APP_ID}. The server consumes them
 * in-process and answers with {@link RetentionPlan} ADUs under the same app ID, which the dropbox parses
 * itself. Both directions are encoded by {@link DropboxControlCodec}.
 *
 * <p>The design is "06 Storage Management Design" in the DDD Dropbox notes.
 */
public final class DropboxControl {
    /**
     * ADU app ID for both directions. It is deliberately <em>not</em> an Android package name: BundleClient's
     * {@code MessageProvider} trusts package-named app IDs, and the server should list this ID only for clients
     * known to be dropboxes, so stock clients never see it (Research/R6 §3, R7 TL;DR).
     */
    public static final String APP_ID = "net.discdd.bundledropbox.control";

    /** Wire version this code writes and the only version it accepts ({@code "v"} field). */
    public static final int VERSION = 1;

    /** {@code "type"} discriminator of an {@link InventoryReport} (dropbox to server). */
    public static final String TYPE_INVENTORY = "inventory";

    /** {@code "type"} discriminator of a {@link RetentionPlan} (server to dropbox). */
    public static final String TYPE_PLAN = "plan";

    /**
     * Largest encoded message accepted or produced, in bytes (4 MiB). An inventory entry is about 200 bytes, so
     * this fits roughly 20,000 entries, far more than a phone-sized dropbox holds. The bound stops a malformed
     * or hostile ADU from exhausting memory on either side.
     */
    public static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    /** Largest number of entries in any one list of a message (upstream, downstream, deletions, keep, ...). */
    public static final int MAX_ENTRIES_PER_LIST = 20_000;

    /**
     * Largest number of {@code planResults} in one inventory. The dropbox only needs to report results not yet
     * reflected in a report the server has seen, which is normally one or two plans.
     */
    public static final int MAX_PLAN_RESULTS = 64;

    /** Longest accepted {@code clientKeyB64} value (a fingerprint is 43 characters; see {@link BundleJarInfo}). */
    public static final int MAX_CLIENT_KEY_CHARS = 128;

    /**
     * Why the server tells a dropbox to delete a bundle. Every reason is a monotonic fact, so applying a stale
     * plan is always safe (Research/R7 §5).
     */
    public enum Reason {
        /** Upstream: the server has received and processed this exact bundle ID. */
        RECEIVED_BY_SERVER,
        /** Upstream: the server already has a newer bundle from the same client. Downstream: the dropbox (or the
         *  client) holds a newer bundle for the same client, and clients ignore older ones. */
        SUPERSEDED,
        /** Downstream: the client has acknowledged this bundle (or a newer one). */
        CLIENT_ACKED,
        /** Either direction: the server cannot map the bundle and it outlived the server's TTL. */
        EXPIRED
    }

    /** Why a dropbox did not delete a bundle a plan listed (reported back in {@code planResults}). */
    public enum SkipReason {
        /** The bundle was not in the named direction of the dropbox store (already gone, or never held). */
        NOT_FOUND,
        /** Deleting the file failed; the dropbox keeps it and the server may list it again. */
        DELETE_FAILED
    }

    private DropboxControl() {}
}
