package net.discdd.bundledropbox.wifi;

import android.annotation.SuppressLint;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;

import net.discdd.bundledropbox.R;
import net.discdd.bundledropbox.cycle.RadioCallback;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * HOST mode of the dropbox radio: makes the phone look exactly like a BundleTransport to an unmodified
 * BundleClient. Adapted from {@code BundleTransport/.../wifi/DDDWifiServer.java} (Research/R1 §1).
 *
 * <p>{@link #start} runs, each step bounded by {@link WifiTimeouts}:
 * <ol>
 *   <li>Inspect the current group. Our own {@code DIRECT-ddd_<baseId>} group with us as GO is reused;
 *       any other group (e.g. a carrier group left over from VISIT) is removed first.</li>
 *   <li>{@code createGroup} with {@code DIRECT-ddd_<baseId>} / {@code ConnectToMe} / 2 GHz / persistent,
 *       then poll {@code requestGroupInfo} until we are observed as group owner. The client connects by
 *       device address and requires the advertiser to be the GO (R3 §1e).</li>
 *   <li>{@link HostingEndpoint#start()} on a worker thread: makes sure the client server (gRPC on 7777,
 *       which runs for the whole service lifetime) is up before anyone can discover us.</li>
 *   <li>{@code clearLocalServices} + {@code addLocalService("ddd", "_ddd._tcp", TXT)} with the TXT from
 *       {@link DropboxTxt#hostRecord}; {@code ready} only after this succeeds.</li>
 * </ol>
 * {@link #stop} clears local services, calls {@link HostingEndpoint#stop()} (ends client sessions), removes
 * the group and reports {@code ready} only once {@code requestGroupInfo} returns null.
 *
 * <p>Losing the group: while HOSTING a connection-changed broadcast without our group reports
 * {@code roleLost} at once. While STARTING (after the group was observed as formed, before the service is
 * added) the broadcast triggers a {@code requestGroupInfo} re-check, because it may be a late broadcast
 * from step 1's removal; if the group is really gone the start fails instead of reporting a stale
 * {@code ready} (audit finding m2). Bookkeeping and the decision live in {@link HostedGroupTracker}.
 *
 * <p>Joined clients: the group's {@code getClientList()} count is reported to a {@link HostedClientsListener}
 * on every change, so the role cycle can avoid idle-switching under a client that joined but has not sent
 * its first RPC yet (audit finding m5).
 *
 * <p>Differences from DDDWifiServer, on purpose:
 * <ul>
 *   <li>Never creates a group on its own (DDDWifiServer re-creates on every P2P-enabled broadcast). Only
 *       the role-cycle controller decides when to host.</li>
 *   <li>Advertises only after the group is formed (DDDWifiServer adds the service immediately).</li>
 *   <li>A missing recency blob does not crash (DDDWifiServer wraps the NPE, R5 §6); the
 *       {@code recencyBlob} key is then omitted so clients simply do not list us.</li>
 * </ul>
 *
 * <p>Threading: main thread only (see {@link P2pLink}). Generation guard: each start/stop is an
 * {@link Op}; a newer op supersedes the old one, and every continuation checks {@link Op#live()}.
 */
@SuppressLint("MissingPermission") // P2pLink.precheck verifies permissions; SecurityException is caught per call
final class DropboxWifiHost {
    private static final Logger logger = Logger.getLogger(DropboxWifiHost.class.getName());

    /** Host lifecycle as seen by the UI. */
    enum State { IDLE, STARTING, HOSTING, STOPPING }

    private final P2pLink link;
    private final String baseId;
    private final Supplier<String> advertisedId;
    private final Supplier<byte[]> recencyBlob;
    private final HostingEndpoint endpoint;
    private final Runnable stateChanged;
    private final Consumer<String> roleLost;
    private final IntConsumer clientCountChanged;
    private final HostedGroupTracker tracker = new HostedGroupTracker();

    private State state = State.IDLE;
    private Op activeOp;
    /** The start op that reached HOSTING; lets a late group re-check tell "still this hosting" apart. */
    private Op hostedBy;
    private boolean endpointRunning;
    private String currentAdvertisedId;
    /** Our group while STARTING (after formation) or HOSTING; null otherwise. */
    private WifiP2pGroup group;
    private InetAddress groupOwnerAddress;

    /**
     * @param link               shared channel and helpers
     * @param baseId             stable 16-char id for the group name
     * @param advertisedId       supplies the TXT {@code transportId} at each start (rotates per 03 Architecture §5)
     * @param recencyBlob        supplies the raw cached {@code GetRecencyBlobResponse}, or null if none yet
     * @param endpoint           client server hooks: ensure it is up before advertising, end sessions on stop
     * @param stateChanged       called after any UI-visible change
     * @param roleLost           called with a reason if the hosted group disappears while HOSTING
     * @param clientCountChanged called with the new P2P client count of our group when it changes
     */
    DropboxWifiHost(P2pLink link,
                    String baseId,
                    Supplier<String> advertisedId,
                    Supplier<byte[]> recencyBlob,
                    HostingEndpoint endpoint,
                    Runnable stateChanged,
                    Consumer<String> roleLost,
                    IntConsumer clientCountChanged) {
        this.link = link;
        this.baseId = baseId;
        this.advertisedId = advertisedId;
        this.recencyBlob = recencyBlob;
        this.endpoint = endpoint;
        this.stateChanged = stateChanged;
        this.roleLost = roleLost;
        this.clientCountChanged = clientCountChanged;
    }

    State state() {
        return state;
    }

    /** @return true while starting, hosting or stopping */
    boolean isActive() {
        return state != State.IDLE;
    }

    String currentAdvertisedId() {
        return currentAdvertisedId;
    }

    /** @return UI view of our group, or null when not hosting */
    WifiRadioState.GroupInfo groupInfo() {
        WifiP2pGroup g = group;
        if (g == null || state == State.IDLE) return null;
        InetAddress addr = groupOwnerAddress != null ? groupOwnerAddress : interfaceAddress(g);
        List<String> clients = g.getClientList().stream().map(DropboxWifiHost::deviceLabel).collect(Collectors.toList());
        return new WifiRadioState.GroupInfo(g.getNetworkName(),
                                            g.getPassphrase(),
                                            addr == null ? null : addr.getHostAddress(),
                                            g.isGroupOwner(),
                                            clients);
    }

    // ---------------------------------------------------------------- start

    /** RadioDriver.startHosting, on the main thread. */
    void start(long gen, RadioCallback cb) {
        supersede("superseded by startHosting gen " + gen);
        Op op = new Op(gen, cb, true);
        activeOp = op;
        String problem = link.precheck(false);
        if (problem != null) {
            op.fail(problem);
            return;
        }
        state = State.STARTING;
        stateChanged.run();
        String expectedName = DropboxTxt.networkName(baseId);
        // Step 1: reuse our own group, otherwise leave whatever group we are in.
        link.pollGroup("requestGroupInfo", WifiTimeouts.SERVICE_OP_MS, g -> true, op::live, g -> {
            if (g != null && g.isGroupOwner() && expectedName.equals(g.getNetworkName())) {
                link.wifiRes(INFO, R.string.wifi_direct_get_group_success);
                onGroupFormed(op, g);
            } else if (g != null) {
                leaveForeignGroupThenCreate(op, g, expectedName);
            } else {
                createGroup(op, expectedName);
            }
        }, op::fail);
    }

    private void leaveForeignGroupThenCreate(Op op, WifiP2pGroup foreign, String expectedName) {
        link.wifi(INFO, "Leaving group " + foreign.getNetworkName() + " before hosting");
        link.removeGroupAndAwaitGone(op::live, (ok, why) -> {
            if (!op.live()) return;
            if (ok) createGroup(op, expectedName);
            else op.fail("could not leave group " + foreign.getNetworkName() + ": " + why);
        });
    }

    /** Step 2: createGroup and wait until we are observed as GO. */
    private void createGroup(Op op, String networkName) {
        long deadline = P2pLink.now() + WifiTimeouts.GROUP_CREATE_MS;
        WifiP2pConfig config = new WifiP2pConfig.Builder().setNetworkName(networkName)
                .setPassphrase(DropboxTxt.PASSPHRASE)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build();
        P2pLink.P2pCall create = (m, c, l) -> m.createGroup(c, config, l);
        link.action("createGroup", WifiTimeouts.GROUP_CREATE_MS, create, op::live, (ok, why) -> {
            if (!op.live()) return;
            if (ok) {
                link.wifiRes(INFO, R.string.wifi_direct_create_group_success);
            } else {
                // Android 14/15 fail createGroup when a (persistent) group already exists, so a failure is
                // not final: we still look for a group we own (same workaround as DDDWifiServer.getGroup()).
                link.wifiRes(WARNING, R.string.wifi_direct_create_group_failed_e, why);
            }
            long remaining = Math.max(WifiTimeouts.POLL_INTERVAL_MS, deadline - P2pLink.now());
            link.pollGroup("waiting for group to form", remaining, DropboxWifiHost::isOwnedGroup, op::live, g -> {
                if (!networkName.equals(g.getNetworkName())) {
                    logger.warning("Hosting group " + g.getNetworkName() + " instead of " + networkName +
                                           "; clients connect by device address so this still works");
                }
                onGroupFormed(op, g);
            }, reason -> op.fail(ok ? "group did not form: " + reason : why + "; " + reason));
        });
    }

    /** Step 3: make sure the client endpoint is up. */
    private void onGroupFormed(Op op, WifiP2pGroup g) {
        if (!op.live()) return;
        link.wifiRes(INFO, R.string.new_group_s, g.getNetworkName());
        trackGroup(g);
        stateChanged.run();
        if (endpointRunning) {
            advertise(op);
            return;
        }
        link.runOnWorker("starting client endpoint", WifiTimeouts.ENDPOINT_MS, endpoint::start, (ok, why) -> {
            // Even if the op was superseded, remember that the endpoint is up so stop() calls its stop hook.
            if (ok) endpointRunning = true;
            if (!op.live()) return;
            if (ok) advertise(op);
            else op.fail(why);
        });
    }

    /** Step 4: (re)register the DNS-SD service; ready on success. */
    private void advertise(Op op) {
        String id;
        Map<String, String> txt;
        try {
            id = advertisedId.get();
            byte[] rawBlob = recencyBlob.get();
            String encoded = DropboxTxt.encodeRecencyBlob(rawBlob);
            if (rawBlob == null) {
                link.wifi(WARNING, "No recency blob yet: clients will not list this dropbox until a carrier visit");
            } else if (encoded == null) {
                // Same refusal as DDDWifiServer.updateRecencyBlob (>255 chars), but we still advertise.
                link.wifi(SEVERE, "Recency blob too large for a TXT record (" + rawBlob.length + " bytes); omitted");
            }
            txt = DropboxTxt.hostRecord(id, encoded);
        } catch (RuntimeException e) {
            op.fail("cannot build TXT record: " + e.getMessage());
            return;
        }
        WifiP2pDnsSdServiceInfo info;
        try {
            info = WifiP2pDnsSdServiceInfo.newInstance(DropboxTxt.INSTANCE_NAME, DropboxTxt.SERVICE_TYPE, txt);
        } catch (IllegalArgumentException e) {
            op.fail("invalid DNS-SD service: " + e.getMessage());
            return;
        }
        P2pLink.P2pCall add = (m, c, l) -> m.addLocalService(c, info, l);
        // clearLocalServices first: a stale record from a previous cycle would advertise an old blob or id.
        link.action("clearLocalServices", WifiTimeouts.SERVICE_OP_MS, P2pLink.CLEAR_LOCAL_SERVICES, op::live, (cleared, whyNot) -> {
            if (!op.live()) return;
            if (!cleared) logger.warning(whyNot);
            link.action("addLocalService", WifiTimeouts.SERVICE_OP_MS, add, op::live, (ok, why) -> {
                if (!op.live()) return;
                if (ok) onAdvertised(op, id);
                else op.fail(why);
            });
        });
    }

    private void onAdvertised(Op op, String id) {
        currentAdvertisedId = id;
        state = State.HOSTING;
        hostedBy = op;
        link.wifi(INFO, "Hosting " + (group == null ? "" : group.getNetworkName()) + " as transportId " + id);
        op.ready();
    }

    /**
     * RadioDriver.withdrawHostAdvertisement, on the main thread: the HOST dwell ended with admitted clients
     * still mid-exchange. Clear the DNS-SD record so no new client discovers a dropbox that is about to
     * refuse it (a refused stock client stamps lastExchange and then skips this transportId, R3 §7), but
     * keep the group and the endpoint so admitted clients finish. Best effort: a failure is only logged,
     * {@link #stop} clears services again anyway. Idempotent (a second clear is harmless).
     *
     * <p>Residual window: a client that already cached our record (or is mid-discovery) can still connect
     * during the drain and be refused; withdrawing early only makes that window as small as the radio allows.
     */
    void withdrawAdvertisement() {
        if (state != State.HOSTING) return;
        link.action("clearLocalServices (drain)",
                    WifiTimeouts.SERVICE_OP_MS,
                    P2pLink.CLEAR_LOCAL_SERVICES,
                    () -> state == State.HOSTING,
                    (ok, why) -> {
                        if (ok) link.wifi(INFO, "Stopped advertising; letting connected clients finish");
                        else logger.warning("could not withdraw advertisement: " + why);
                    });
    }

    // ---------------------------------------------------------------- stop

    /** RadioDriver.stopHosting, on the main thread. */
    void stop(long gen, RadioCallback cb) {
        supersede("cancelled by stopHosting gen " + gen);
        Op op = new Op(gen, cb, false);
        activeOp = op;
        state = State.STOPPING;
        stateChanged.run();
        if (link.manager == null || !link.ensureChannel()) {
            stopEndpoint(op, () -> finishStop(op));
            return;
        }
        // Order: stop being discoverable, end client sessions, then leave the group (03 Architecture, RadioDriver).
        link.action("clearLocalServices", WifiTimeouts.SERVICE_OP_MS, P2pLink.CLEAR_LOCAL_SERVICES, op::live, (ok, why) -> {
            if (!op.live()) return;
            if (!ok) logger.warning(why);
            stopEndpoint(op, () -> removeGroupThenFinish(op));
        });
    }

    private void removeGroupThenFinish(Op op) {
        link.removeGroupAndAwaitGone(op::live, (removed, whyNot) -> {
            if (!op.live()) return;
            if (removed) finishStop(op);
            else op.fail(whyNot);
        });
    }

    private void stopEndpoint(Op op, Runnable then) {
        if (!endpointRunning) {
            then.run();
            return;
        }
        link.runOnWorker("stopping client endpoint", WifiTimeouts.ENDPOINT_MS, endpoint::stop, (ok, why) -> {
            endpointRunning = false; // a failed stop is not retried; the endpoint must tolerate stop/start
            if (!ok) logger.warning(why);
            if (op.live()) then.run();
        });
    }

    /**
     * A START_HOST failed after the endpoint came up. The controller then falls back to VISIT without a
     * STOP_HOST, so this is the only place that ends client sessions admitted (and PSI sessions created)
     * during the failed start (audit m22). Best effort, fire and forget; {@code endpointRunning} is cleared
     * first so a following start re-runs the (idempotent) endpoint start hook.
     */
    private void endSessionsAfterFailedStart() {
        if (!endpointRunning) return;
        endpointRunning = false;
        link.runOnWorker("ending client sessions after a failed start", WifiTimeouts.ENDPOINT_MS, endpoint::stop, (ok, why) -> {
            if (!ok) logger.warning(why);
        });
    }

    private void finishStop(Op op) {
        forgetGroup();
        state = State.IDLE;
        op.ready();
    }

    /**
     * Fails the in-flight op (if any) so its callback still fires exactly once with its own generation.
     */
    private void supersede(String reason) {
        Op old = activeOp;
        if (old != null) old.fail(reason);
    }

    /**
     * Wi-Fi Direct was disabled or the channel was lost: any in-flight start/stop fails now rather than at
     * its timeout, and HOSTING ends without a group.
     *
     * @return true if this ended an established HOSTING role (the driver reports it unless an availability
     *         edge already does); a failed in-flight op reports itself through its callback instead
     */
    boolean onRadioLost(String reason) {
        if (activeOp != null) activeOp.fail(reason);
        if (state != State.HOSTING) return false;
        forgetGroup();
        state = State.IDLE;
        stateChanged.run();
        return true;
    }

    // ---------------------------------------------------------------- group tracking

    /**
     * WIFI_P2P_CONNECTION_CHANGED_ACTION: track our group's GO address and clients; detect its loss.
     */
    void onConnectionChanged(WifiP2pInfo info, WifiP2pGroup newGroup) {
        if (state != State.STARTING && state != State.HOSTING) return;
        boolean ours = isOwnedGroup(newGroup);
        switch (tracker.onConnectionChanged(ours, ours ? clientsOf(newGroup) : Map.of())) {
            case NOT_FORMED -> {
                // Step 1 leaving a stale group, or createGroup still in progress: the poll decides.
            }
            case UPDATED -> {
                group = newGroup;
                if (info != null && info.groupOwnerAddress != null) groupOwnerAddress = info.groupOwnerAddress;
                onClientsChanged(tracker.lastChange());
                stateChanged.run();
            }
            case LOST -> {
                onClientsChanged(tracker.lastChange());
                if (state == State.HOSTING) {
                    onHostedGroupLost();
                } else if (activeOp != null) {
                    // STARTING after formation. A connection-changed broadcast can arrive late (e.g. from
                    // step 1's removal), so confirm with requestGroupInfo before failing the start.
                    recheckGroup(activeOp);
                }
            }
        }
    }

    /**
     * Confirms with {@code requestGroupInfo} that our group still exists. If it does not, fails {@code op}
     * while it is still starting, or reports role loss if {@code op} meanwhile reached HOSTING.
     */
    private void recheckGroup(Op op) {
        BooleanSupplier stillRelevant = () -> op.live() || (state == State.HOSTING && hostedBy == op);
        link.pollGroup("re-checking hosted group", WifiTimeouts.SERVICE_OP_MS, g -> true, stillRelevant, g -> {
            if (!stillRelevant.getAsBoolean()) return;
            if (isOwnedGroup(g)) {
                trackGroup(g); // the broadcast was stale
                stateChanged.run();
            } else if (op.live()) {
                op.fail("hosted group lost while starting");
            } else {
                onHostedGroupLost();
            }
        }, reason -> logger.fine(reason + " (keeping the current hosting state)"));
    }

    private void onHostedGroupLost() {
        link.wifiRes(WARNING, R.string.left_group_s, group == null ? "" : group.getNetworkName());
        forgetGroup();
        state = State.IDLE;
        stateChanged.run();
        roleLost.accept("hosted group lost");
    }

    /** Records {@code g} as our formed group and reports its clients. */
    private void trackGroup(WifiP2pGroup g) {
        group = g;
        onClientsChanged(tracker.formed(clientsOf(g)));
    }

    /** Forgets the group; reports a drop of the client count to 0. */
    private void forgetGroup() {
        group = null;
        groupOwnerAddress = null;
        hostedBy = null;
        onClientsChanged(tracker.reset());
    }

    /** Client diff logging copied from DDDWifiServer's receiver, plus the count for the role cycle. */
    private void onClientsChanged(HostedGroupTracker.ClientChange change) {
        if (!change.joined.isEmpty()) link.wifiRes(INFO, R.string.new_clients_s, String.join(", ", change.joined));
        if (!change.left.isEmpty()) link.wifiRes(INFO, R.string.lost_clients_s, String.join(", ", change.left));
        if (change.countChanged) clientCountChanged.accept(change.count);
    }

    private static boolean isOwnedGroup(WifiP2pGroup g) {
        return g != null && g.isGroupOwner();
    }

    /** @return the group's clients keyed by P2P device address (label as fallback key) */
    private static Map<String, String> clientsOf(WifiP2pGroup g) {
        Map<String, String> out = new LinkedHashMap<>();
        for (WifiP2pDevice d : g.getClientList()) {
            String label = deviceLabel(d);
            out.put(d.deviceAddress == null || d.deviceAddress.isBlank() ? label : d.deviceAddress, label);
        }
        return out;
    }

    private static String deviceLabel(WifiP2pDevice d) {
        return d.deviceName == null || d.deviceName.isBlank() ? d.deviceAddress : d.deviceName;
    }

    /** GO IPv4 address from the group's interface, as DDDWifiServer.getNetworkInfo() does. */
    private static InetAddress interfaceAddress(WifiP2pGroup g) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(g.getInterface());
            if (ni == null) return null;
            return Collections.list(ni.getInetAddresses())
                    .stream()
                    .filter(a -> a instanceof Inet4Address)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- op / generation guard

    /**
     * One start or stop request. Exactly one of ready/fail reaches the controller, carrying {@link #gen};
     * once finished or superseded, {@link #live()} is false and every pending continuation drops out.
     */
    private final class Op {
        final long gen;
        final RadioCallback cb;
        /** true for startHosting, false for stopHosting */
        final boolean starting;
        boolean finished;

        Op(long gen, RadioCallback cb, boolean starting) {
            this.gen = gen;
            this.cb = cb;
            this.starting = starting;
        }

        boolean live() {
            return !finished && activeOp == this;
        }

        void ready() {
            if (finished) return;
            finished = true;
            if (activeOp == this) activeOp = null;
            stateChanged.run();
            cb.ready(gen);
        }

        void fail(String reason) {
            if (finished) return;
            finished = true;
            boolean current = activeOp == this;
            if (current) activeOp = null;
            link.wifi(SEVERE, "Hosting step failed (gen " + gen + "): " + reason);
            // A failed start/stop makes no claim of HOSTING. A group left behind is reused or removed by the
            // next startHosting (step 1 inspects the group) or by stopHosting/startVisit (both remove any
            // group). If the cycle halts right after a failed stop, the controller records the role as dirty
            // and re-issues stopHosting before its next start or at its next halt (audit m4/m22).
            if (current) {
                state = State.IDLE;
                forgetGroup();
                if (starting) endSessionsAfterFailedStart();
            }
            stateChanged.run();
            cb.failed(gen, reason);
        }
    }
}
