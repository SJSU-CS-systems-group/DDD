package net.discdd.bundledropbox.store;

/**
 * Records which DOWNSTREAM bundles the dropbox has served to clients.
 *
 * <p>Nothing is deleted based on it. "Served" means the {@code psiDownloadBundle} stream for that bundle
 * completed without error; it is not proof the client processed it. The counts are reported to the server in
 * the dropbox's inventory ({@code control/InventoryBuilder}) as advisory evidence; the server deletes a pickup
 * only on the client's own ack (see {@link RetentionPolicy}).
 */
public interface DeliveryLog {
    /**
     * @param downstreamBundleId encrypted id of the DOWNSTREAM bundle that was streamed to a client
     * @param atMillis           wall-clock time the stream completed
     */
    void recordServed(String downstreamBundleId, long atMillis);
}
