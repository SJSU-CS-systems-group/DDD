package net.discdd.bundledropbox.cycle;

/**
 * The radio side of the role cycle: everything that touches Wi-Fi Direct and DNS-SD, behind an interface
 * so {@code RoleCycleController} stays pure Java and testable.
 *
 * <p>Implemented by {@code wifi/WifiDirectRadioDriver}; faked in unit tests.
 *
 * <p>The client-facing gRPC server (port 7777) is <em>not</em> started and stopped per role: it listens on
 * the wildcard address for the whole service lifetime ({@code rpc/DropboxRpcServer}). Who may use it is
 * decided by the {@link ActivityGate} (opened at every START, closed at dwell end), not by the socket. The
 * HOST calls below only bracket the role with the service's {@code HostingEndpoint}: {@code start()} makes
 * sure the server is listening (retrying a failed bind) before the DNS-SD record is published, and
 * {@code stop()} ends the role's client and PSI sessions ({@code endClientSessions}).
 *
 * <p>Contract for every method:
 * <ul>
 *   <li>Non-blocking: return immediately and report completion through the callback.</li>
 *   <li>{@code gen} is an opaque, increasing number chosen by the controller; echo it back unchanged.</li>
 *   <li>The controller never overlaps operations: it waits for {@code ready}/{@code failed} before the
 *       next call (except {@code stopVisit}/{@code stopHosting}, which may be issued while work is
 *       still in flight to cancel it).</li>
 * </ul>
 */
public interface RadioDriver {
    /**
     * Enter HOST mode exactly like a BundleTransport: create Wi-Fi Direct group
     * {@code DIRECT-ddd_<baseId>} (passphrase {@code ConnectToMe}, 2 GHz, persistent), make sure the
     * always-running gRPC TLS server on 7777 is listening ({@code HostingEndpoint.start()}), and advertise
     * DNS-SD {@code ddd}/{@code _ddd._tcp} with TXT {@code ddd}, {@code transportId}, {@code recencyBlob} and
     * {@code dropbox:"1"} (Research/R1 §1, 03 Architecture).
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once the group is formed and the service is added; {@code failed} otherwise
     */
    void startHosting(long gen, RadioCallback cb);

    /**
     * Leave HOST mode: clear local services, end the role's client sessions ({@code HostingEndpoint.stop()};
     * the server keeps listening) and remove the group. Must be idempotent: the controller also issues it
     * when it is unsure whether a failed start or stop left a group or service behind.
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once group removal has been observed
     */
    void stopHosting(long gen, RadioCallback cb);

    /**
     * Number of P2P clients currently joined to the hosted group, from the last known
     * {@code WifiP2pGroup.getClientList()} (0 when not hosting or unknown). Called on the controller's owner
     * thread when a HOST dwell ends, and polled every {@code JOINED_CLIENT_POLL_MS} during the joined-client
     * grace, so it must be cheap and non-blocking (return a cached value; never call into
     * {@code WifiP2pManager} here).
     *
     * <p>Why: a stock client that has joined the group but not yet sent its first RPC (server probe, TLS
     * handshake) holds no gate token, so an idle-looking HOST would tear the group down under it; the
     * refused client stamps {@code lastExchange} and is locked out until the advertised id rotates
     * (Audit m5, R3 §7). With this count the controller keeps admissions open for a short bounded grace.
     * Implementations should also call {@code RoleCycleController.hostedClientsChanged()} when the client
     * list changes, so the grace ends without waiting for the next poll (optional).
     *
     * @return joined client count; the default (no information) disables the grace
     */
    default int hostedClientCount() {
        return 0;
    }

    /**
     * The HOST dwell ended while admitted (or joined) clients are still mid-exchange (HOST_DRAINING): stop
     * being discoverable now (clear the DNS-SD record) but keep the group and the client sessions so they
     * can finish. Best effort and fire-and-forget: no callback; {@link #stopHosting} later clears
     * services again anyway.
     *
     * <p>Why this exists: during the drain the gate refuses new clients with UNAVAILABLE, and the stock
     * client stamps {@code lastExchange} for the advertised transportId even when its exchange failed
     * ({@code BundleClientService.exchangeWith} calls {@code timeStampExchange} unconditionally, R3 §7).
     * It then skips this transportId until the blob timestamp exceeds that stamp, i.e. a client that
     * discovers us while we drain is locked out until the advertised id rotates. Withdrawing the record
     * keeps new clients from discovering a dropbox that is about to refuse them.
     */
    void withdrawHostAdvertisement();

    /**
     * Enter VISIT mode: discover {@code _ddd._tcp} peers that publish {@code recencyBlob} and have no
     * {@code dropbox} key (so dropboxes never visit each other), join the first one as a P2P client
     * (GO intent 0), run the carrier exchange, disconnect, and keep discovering.
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} when discovery has started; {@code visitFinished} after each carrier exchange
     */
    void startVisit(long gen, VisitCallback cb);

    /**
     * Leave VISIT mode: cancel any connect/exchange in progress, leave the carrier's group and stop
     * discovery. Partial downloads are aborted (staged files discarded) and stay retryable. Must be
     * idempotent, like {@link #stopHosting}.
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once the radio is idle
     */
    void stopVisit(long gen, RadioCallback cb);
}
