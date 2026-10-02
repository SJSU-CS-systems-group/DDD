package net.discdd.bundledropbox.wifi;

/**
 * The client-facing server that must be running while the dropbox hosts its group: in v1 the gRPC TLS
 * {@code BundleExchangeService} on port 7777 ({@code rpc/DropboxRpcServer}).
 *
 * <p>{@code RadioDriver.startHosting} is specified as "create group, start gRPC, advertise", so the
 * Wi-Fi host calls {@link #start()} after the group has formed and <em>before</em> adding the DNS-SD
 * service: a stock client connects to {@code groupOwnerAddress:7777} as soon as it sees the TXT record and
 * gives up after about 4 s of failed socket probes (R3 §7). {@link #stop()} is called after the service is
 * cleared and before the group is removed.
 *
 * <p>Both methods are called on a driver worker thread (may block, bounded by
 * {@link WifiTimeouts#ENDPOINT_MS}) and must be idempotent: start on a running endpoint and stop on a
 * stopped one are no-ops.
 */
public interface HostingEndpoint {
    /** An endpoint that does nothing (tests, or a service that runs its server permanently). */
    HostingEndpoint NONE = new HostingEndpoint() {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    /**
     * Starts serving clients.
     *
     * @throws Exception if the server cannot start; startHosting then fails with this message
     */
    void start() throws Exception;

    /** Stops serving clients; must not throw for an already stopped endpoint. */
    void stop() throws Exception;
}
