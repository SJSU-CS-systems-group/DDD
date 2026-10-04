package net.discdd.bundledropbox.wifi;

/**
 * Hooks around each HOST role for the client-facing server: in v1 the gRPC TLS
 * {@code BundleExchangeService} on port 7777 ({@code rpc/DropboxRpcServer}). The service runs that server
 * for its whole lifetime (bound to the wildcard address, admission decided by the role cycle's gate), so
 * these hooks do not open or close the port.
 *
 * <p>The Wi-Fi host calls {@link #start()} after the group has formed and <em>before</em> adding the DNS-SD
 * service, to make sure the server is up (e.g. retry a bind that failed at service start): a stock client
 * connects to {@code groupOwnerAddress:7777} as soon as it sees the TXT record and gives up after about 4 s
 * of failed socket probes (R3 §7). {@link #stop()} is called after the service is cleared and before the
 * group is removed; it ends the HOST role's client sessions.
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
