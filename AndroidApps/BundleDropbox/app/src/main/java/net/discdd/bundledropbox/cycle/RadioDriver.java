package net.discdd.bundledropbox.cycle;

/**
 * The radio side of the role cycle: everything that touches Wi-Fi Direct, DNS-SD and the gRPC
 * endpoints, behind an interface so {@code RoleCycleController} stays pure Java and testable.
 *
 * <p>Implemented by {@code wifi/WifiDirectRadioDriver}; faked in unit tests.
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
     * {@code DIRECT-ddd_<baseId>} (passphrase {@code ConnectToMe}, 2 GHz, persistent), start the gRPC TLS
     * server on 7777, and advertise DNS-SD {@code ddd}/{@code _ddd._tcp} with TXT {@code ddd},
     * {@code transportId}, {@code recencyBlob} and {@code dropbox:"1"} (Research/R1 §1, 03 Architecture).
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once the group is formed and the service is added; {@code failed} otherwise
     */
    void startHosting(long gen, RadioCallback cb);

    /**
     * Leave HOST mode: stop the gRPC server, remove the group and clear local services.
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once group removal has been observed
     */
    void stopHosting(long gen, RadioCallback cb);

    /**
     * The HOST dwell ended while admitted clients are still mid-exchange (HOST_DRAINING): stop being
     * discoverable now (clear the DNS-SD record) but keep the group and the gRPC endpoint up so admitted
     * clients can finish. Best effort and fire-and-forget: no callback; {@link #stopHosting} later clears
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
     * discovery. Partial downloads are aborted (staged files discarded) and stay retryable.
     *
     * @param gen generation for this operation
     * @param cb  {@code ready} once the radio is idle
     */
    void stopVisit(long gen, RadioCallback cb);
}
