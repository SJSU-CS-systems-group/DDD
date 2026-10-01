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
