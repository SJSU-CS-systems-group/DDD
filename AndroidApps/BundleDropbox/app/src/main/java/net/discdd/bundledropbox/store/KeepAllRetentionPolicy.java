package net.discdd.bundledropbox.store;

/**
 * v1 {@link RetentionPolicy}: never deletes a relayed bundle.
 *
 * <p>Why: the dropbox cannot tell whether an UPSTREAM bundle reached the server (carriers do not report
 * back) or whether a DOWNSTREAM bundle was processed by its client (the stock client's ack travels
 * inside its next encrypted upstream bundle, which only the server can read; Research/R3 §6, R4 §4.3).
 * Deleting on a guess could lose mail, so v1 keeps everything, bounded only by the
 * {@code max_storage_mb} capacity, and the operator can clear storage from the Storage tab.
 *
 * <p>FUTURE: replaced by a server-managed policy. The server will send keep/delete control ADUs to the
 * dropbox's own control endpoint, informed by client ACK reports built from {@link DeliveryLog}; the
 * policy will then call {@link RelayStore#delete(Direction, String)} for released bundles.
 */
public final class KeepAllRetentionPolicy implements RetentionPolicy {
    /** Shared instance; the policy is stateless. */
    public static final KeepAllRetentionPolicy INSTANCE = new KeepAllRetentionPolicy();

    private KeepAllRetentionPolicy() {}

    /**
     * Placeholder for the future decision hook (not on the {@link RetentionPolicy} contract yet).
     *
     * @param record a committed bundle
     * @return always false in v1: nothing may be deleted automatically
     */
    public boolean mayDelete(BundleRecord record) {
        // FUTURE: consult server keep/delete decisions here.
        return false;
    }

    @Override
    public String toString() {
        return "KeepAllRetentionPolicy";
    }
}
