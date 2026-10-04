package net.discdd.bundledropbox.visit;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The dropbox's own DDD client endpoint as seen by a carrier visit ({@link TransportVisitClient}). The visit
 * splices it into the exchange it already runs (Research/R6 §3.1, 06 Storage Management Design "Visit flow"):
 * <ol>
 *   <li>after the recency blob, it uploads {@link #ownBundleForVisit()} <b>first</b>, before any relayed bundle,
 *       so the inventory and the acks it carries are never starved by a short dwell;</li>
 *   <li>during download-all, every downloaded jar is {@linkplain #classify classified}: a bundle addressed to the
 *       dropbox goes to {@link #accept} and <b>never</b> into the relay store, since no stock client could ever
 *       PSI-match it.</li>
 * </ol>
 * The own bundle is not stored in the relay store or the forward ledger; regenerating it is idempotent.
 *
 * <p>Production implementation: {@code control/DropboxControlManager}. {@link #NONE} turns the channel off
 * (relay-only behaviour, used when the endpoint cannot be opened, and by tests). Implementations must be
 * thread-safe; the visit calls them from its worker thread.
 */
public interface ControlChannel {
    /** How a downloaded bundle relates to the dropbox's own endpoint. */
    enum Ownership {
        /** Some client's bundle: relay it. */
        NOT_MINE,
        /** Addressed to the dropbox and newer than anything it processed: hand it to {@link #accept}. */
        MINE_NEW,
        /** Addressed to the dropbox but already processed (the server re-offers its newest bundle): drop it. */
        MINE_ALREADY_PROCESSED
    }

    /** The dropbox's own upstream bundle for this visit. */
    final class OwnBundle {
        private final String bundleId;
        private final Path path;

        /**
         * @param bundleId the encrypted bundle id (the jar's {@code bundle.id} and the upload name)
         * @param path     the jar on disk; owned by the endpoint, read-only for the visit
         */
        public OwnBundle(String bundleId, Path path) {
            this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
            this.path = Objects.requireNonNull(path, "path");
        }

        /** @return the encrypted bundle id */
        public String bundleId() { return bundleId; }

        /** @return the jar file */
        public Path path() { return path; }

        @Override
        public String toString() { return "OwnBundle{" + bundleId + "}"; }
    }

    /**
     * Builds (or reuses) the dropbox's own bundle: a fresh inventory report when the holdings changed, plus the
     * ack of the newest plan bundle it processed.
     *
     * @return the bundle to upload first, or {@code null} if there is none (endpoint unavailable)
     */
    OwnBundle ownBundleForVisit();

    /**
     * @param bundleId the {@code bundle.id} of a downloaded jar
     * @return whether it is the dropbox's own bundle, and whether it is new
     */
    Ownership classify(String bundleId);

    /**
     * Processes a bundle {@link #classify} called {@link Ownership#MINE_NEW}. The caller still owns
     * {@code stagedJar} and discards it afterwards; the endpoint works on its own copy.
     *
     * @param stagedJar       the downloaded jar
     * @param carrierSenderId the carrier's recency-blob {@code senderId} (the transport id the server routes by),
     *                        or {@code null} if unknown
     * @return true if the bundle was processed (its plans applied)
     */
    boolean accept(Path stagedJar, String carrierSenderId);

    /** Relay-only: no own bundle, nothing is ever addressed to the dropbox. */
    ControlChannel NONE = new ControlChannel() {
        @Override
        public OwnBundle ownBundleForVisit() { return null; }

        @Override
        public Ownership classify(String bundleId) { return Ownership.NOT_MINE; }

        @Override
        public boolean accept(Path stagedJar, String carrierSenderId) { return false; }

        @Override
        public String toString() { return "ControlChannel.NONE"; }
    };
}
