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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>{@link HostingEndpoint#start()} (gRPC on 7777) on a worker thread.</li>
 *   <li>{@code clearLocalServices} + {@code addLocalService("ddd", "_ddd._tcp", TXT)} with the TXT from
 *       {@link DropboxTxt#hostRecord}; {@code ready} only after this succeeds.</li>
 * </ol>
 * {@link #stop} clears local services, stops the endpoint, removes the group and reports {@code ready}
 * only once {@code requestGroupInfo} returns null.
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
    private final Runnable groupLost;

    private State state = State.IDLE;
    private Op activeOp;
    private boolean endpointRunning;
    private WifiP2pDnsSdServiceInfo serviceInfo;
    private String currentAdvertisedId;
    /** Our group while STARTING/HOSTING (null otherwise). */
    private WifiP2pGroup group;
    private InetAddress groupOwnerAddress;

    /**
     * @param link         shared channel and helpers
     * @param baseId       stable 16-char id for the group name
     * @param advertisedId supplies the TXT {@code transportId} at each start (rotates per 03 Architecture §5)
     * @param recencyBlob  supplies the raw cached {@code GetRecencyBlobResponse}, or null if none yet
     * @param endpoint     client-facing server started/stopped with the group
     * @param stateChanged called after any UI-visible change
     * @param groupLost    called if the hosted group disappears while HOSTING
     */
    DropboxWifiHost(P2pLink link,
                    String baseId,
                    Supplier<String> advertisedId,
                    Supplier<byte[]> recencyBlob,
                    HostingEndpoint endpoint,
                    Runnable stateChanged,
                    Runnable groupLost) {
        this.link = link;
        this.baseId = baseId;
        this.advertisedId = advertisedId;
        this.recencyBlob = recencyBlob;
        this.endpoint = endpoint;
        this.stateChanged = stateChanged;
        this.groupLost = groupLost;
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
        Op op = new Op(gen, cb);
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
                link.wifi(INFO, "Leaving group " + g.getNetworkName() + " before hosting");
                link.removeGroupAndAwaitGone(op::live, (ok, why) -> {
                    if (!op.live()) return;
                    if (ok) createGroup(op, expectedName);
                    else op.fail("could not leave group " + g.getNetworkName() + ": " + why);
                });
            } else {
                createGroup(op, expectedName);
            }
        }, op::fail);
    }

    /** Step 2: createGroup and wait until we are observed as GO. */
    private void createGroup(Op op, String networkName) {
        long deadline = P2pLink.now() + WifiTimeouts.GROUP_CREATE_MS;
        WifiP2pConfig config = new WifiP2pConfig.Builder().setNetworkName(networkName)
                .setPassphrase(DropboxTxt.PASSPHRASE)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build();
        link.action("createGroup", WifiTimeouts.GROUP_CREATE_MS, (m, c, l) -> m.createGroup(c, config, l), op::live, (ok, why) -> {
            if (!op.live()) return;
            if (ok) {
                link.wifiRes(INFO, R.string.wifi_direct_create_group_success);
            } else {
                // Android 14/15 fail createGroup when a (persistent) group already exists, so a failure is
                // not final: we still look for a group we own (same workaround as DDDWifiServer.getGroup()).
                link.wifiRes(WARNING, R.string.wifi_direct_create_group_failed_e, why);
            }
            long remaining = Math.max(WifiTimeouts.POLL_INTERVAL_MS, deadline - P2pLink.now());
            link.pollGroup("waiting for group to form",
                           remaining,
                           g -> g != null && g.isGroupOwner(),
                           op::live,
                           g -> {
                               if (!networkName.equals(g.getNetworkName())) {
                                   logger.warning("Hosting group " + g.getNetworkName() + " instead of " + networkName +
                                                          "; clients connect by device address so this still works");
                               }
                               onGroupFormed(op, g);
                           },
                           reason -> op.fail(ok ? "group did not form: " + reason : why + "; " + reason));
        });
    }

    /** Step 3: start the client-facing endpoint. */
    private void onGroupFormed(Op op, WifiP2pGroup g) {
        if (!op.live()) return;
        group = g;
        stateChanged.run();
        if (endpointRunning) {
            advertise(op);
            return;
        }
        link.runOnWorker("starting client endpoint", WifiTimeouts.ENDPOINT_MS, endpoint::start, (ok, why) -> {
            // Even if the op was superseded, remember that the endpoint is up so stop() shuts it down.
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
        // clearLocalServices first: a stale record from a previous cycle would advertise an old blob or id.
        link.action("clearLocalServices", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.clearLocalServices(c, l), op::live, (cleared, whyNot) -> {
            if (!op.live()) return;
            if (!cleared) logger.warning(whyNot);
            link.action("addLocalService", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.addLocalService(c, info, l), op::live, (ok, why) -> {
                if (!op.live()) return;
                if (!ok) {
                    op.fail(why);
                    return;
                }
                serviceInfo = info;
                currentAdvertisedId = id;
                state = State.HOSTING;
                link.wifi(INFO, "Hosting " + (group == null ? "" : group.getNetworkName()) + " as transportId " + id);
                op.ready();
            });
        });
    }

    /**
     * Re-publishes the TXT record while HOSTING (e.g. after a newer recency blob or a rotated advertised
     * id). Remove-then-add, as DDDWifiServer.updateRecencyBlob does. Best effort: failures are logged.
     */
    void refreshAdvertisement() {
        if (state != State.HOSTING || activeOp != null) return;
        Op op = new Op(-1, null);
        activeOp = op;
        state = State.STARTING;
        advertise(op);
    }

    // ---------------------------------------------------------------- stop

    /** RadioDriver.stopHosting, on the main thread. */
    void stop(long gen, RadioCallback cb) {
        supersede("cancelled by stopHosting gen " + gen);
        Op op = new Op(gen, cb);
        activeOp = op;
        state = State.STOPPING;
        stateChanged.run();
        if (link.manager == null || !link.ensureChannel()) {
            stopEndpoint(op, () -> finishStop(op));
            return;
        }
        // Order: stop being discoverable, stop serving, then leave the group (03 Architecture, RadioDriver).
        link.action("clearLocalServices", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.clearLocalServices(c, l), op::live, (ok, why) -> {
            if (!op.live()) return;
            if (!ok) logger.warning(why);
            serviceInfo = null;
            stopEndpoint(op, () -> link.removeGroupAndAwaitGone(op::live, (removed, whyNot) -> {
                if (!op.live()) return;
                if (removed) finishStop(op);
                else op.fail(whyNot);
            }));
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

    private void finishStop(Op op) {
        group = null;
        groupOwnerAddress = null;
        serviceInfo = null;
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
     * Called when Wi-Fi Direct is disabled: any in-flight start/stop fails now rather than at its timeout.
     */
    void onP2pDisabled() {
        if (activeOp != null) activeOp.fail("Wi-Fi Direct was disabled");
        if (state == State.HOSTING) {
            state = State.IDLE;
            group = null;
            stateChanged.run();
            groupLost.run();
        }
    }

    // ---------------------------------------------------------------- broadcasts

    /**
     * WIFI_P2P_CONNECTION_CHANGED_ACTION while hosting: track our group, its GO address and client list.
     * The client diff logging is copied from DDDWifiServer's receiver.
     */
    void onConnectionChanged(WifiP2pInfo info, WifiP2pGroup newGroup) {
        if (state == State.IDLE || state == State.STOPPING) return;
        if (newGroup == null || !newGroup.isGroupOwner()) {
            if (state == State.HOSTING && group != null) {
                link.wifiRes(WARNING, R.string.left_group_s, group.getNetworkName());
                group = null;
                state = State.IDLE;
                stateChanged.run();
                groupLost.run();
            }
            return;
        }
        WifiP2pGroup old = group;
        if (old != null) logClientChanges(old, newGroup);
        else link.wifiRes(INFO, R.string.new_group_s, newGroup.getNetworkName());
        group = newGroup;
        if (info != null && info.groupOwnerAddress != null) groupOwnerAddress = info.groupOwnerAddress;
        stateChanged.run();
    }

    private void logClientChanges(WifiP2pGroup oldGroup, WifiP2pGroup newGroup) {
        Set<String> now = newGroup.getClientList().stream().map(DropboxWifiHost::deviceLabel).collect(Collectors.toSet());
        Set<String> before = oldGroup.getClientList().stream().map(DropboxWifiHost::deviceLabel).collect(Collectors.toSet());
        Set<String> joined = new HashSet<>(now);
        joined.removeAll(before);
        Set<String> left = new HashSet<>(before);
        left.removeAll(now);
        if (!joined.isEmpty()) link.wifiRes(INFO, R.string.new_clients_s, String.join(", ", joined));
        if (!left.isEmpty()) link.wifiRes(INFO, R.string.lost_clients_s, String.join(", ", left));
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
        final RadioCallback cb; // null for internal refreshes
        boolean finished;

        Op(long gen, RadioCallback cb) {
            this.gen = gen;
            this.cb = cb;
        }

        boolean live() {
            return !finished && activeOp == this;
        }

        void ready() {
            if (finished) return;
            finished = true;
            if (activeOp == this) activeOp = null;
            stateChanged.run();
            if (cb != null) cb.ready(gen);
        }

        void fail(String reason) {
            if (finished) return;
            finished = true;
            boolean current = activeOp == this;
            if (current) activeOp = null;
            if (cb == null) {
                // A failed TXT refresh leaves us hosting with the previous advertisement (or none).
                logger.warning("advertisement refresh failed: " + reason);
                if (current) state = State.HOSTING;
                stateChanged.run();
                return;
            }
            link.wifi(SEVERE, "Hosting step failed (gen " + gen + "): " + reason);
            // A failed start/stop makes no claim of HOSTING. Whatever is left behind (group, endpoint) is
            // reused or removed by the controller's next start/stop, which always inspects the group first.
            if (current) state = State.IDLE;
            stateChanged.run();
            cb.failed(gen, reason);
        }
    }
}
