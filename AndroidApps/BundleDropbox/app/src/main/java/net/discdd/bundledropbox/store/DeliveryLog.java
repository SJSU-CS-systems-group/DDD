package net.discdd.bundledropbox.store;

/**
 * Records which DOWNSTREAM bundles the dropbox has served to clients.
 *
 * <p>v1 only logs (for the UI and debugging); nothing is deleted based on it. "Served" means the
 * {@code psiDownloadBundle} stream for that bundle completed without error; it is not proof the client
 * processed it.
 *
 * FUTURE: basis for client-ACK report ADUs. Once the dropbox has its own control endpoint, it will
 * report served/acknowledged bundles to the server, which will then issue keep/delete decisions
 * (see {@link RetentionPolicy}).
 */
public interface DeliveryLog {
    /**
     * @param downstreamBundleId encrypted id of the DOWNSTREAM bundle that was streamed to a client
     * @param atMillis           wall-clock time the stream completed
     */
    void recordServed(String downstreamBundleId, long atMillis);
}
