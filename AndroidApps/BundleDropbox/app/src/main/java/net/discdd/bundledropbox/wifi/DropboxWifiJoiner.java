package net.discdd.bundledropbox.wifi;

import android.annotation.SuppressLint;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;

import net.discdd.bundledropbox.cycle.RadioCallback;
import net.discdd.bundledropbox.cycle.VisitCallback;
import net.discdd.utils.UserLogRepository;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * VISIT mode of the dropbox radio: behaves like an unmodified BundleClient looking for a transport, so an
 * unmodified BundleTransport (the "carrier") sees the dropbox as just another client. Adapted from
 * {@code BundleClient/.../service/wifiDirect/DDDWifiDirect.java} (Research/R2 §1.2-1.3).
 *
 * <p>One visit period ({@link #start} .. {@link #stop}):
 * <ol>
 *   <li>Leave any group and clear our local service, so clients stop seeing us while we are away
 *       (R3 §7: otherwise they time out on a dropbox that is not serving).</li>
 *   <li>Register {@code WifiP2pDnsSdServiceRequest("ddd", "_ddd._tcp")} and {@code discoverServices};
 *       {@code VisitCallback.ready} fires when discovery has started (that starts the transport dwell
 *       timer). Discovery is re-issued every {@link WifiTimeouts#REDISCOVER_INTERVAL_MS} while idle.</li>
 *   <li>TXT answers are filtered by {@link DropboxTxt#parseCarrier} (recencyBlob + transportId, no
 *       {@code dropbox} key) and fed to {@link CarrierSelector}.</li>
 *   <li>Join the selected carrier like the client does: {@code WifiP2pConfig.deviceAddress}, plus
 *       {@code groupOwnerIntent = 0} so the carrier stays GO (its clients need it as GO). Wait for
 *       {@code WIFI_P2P_CONNECTION_CHANGED_ACTION} with {@code EXTRA_WIFI_P2P_INFO.groupOwnerAddress}
 *       (polling {@code requestConnectionInfo} as fallback), verify we are <em>not</em> GO and that the GO
 *       is the carrier we asked for (the client's {@code sameAddressAs} check).</li>
 *   <li>Run the integrator's {@link VisitRunner} on a worker thread with (GO address, carrier id).</li>
 *   <li>Report {@code visitFinished(gen, success)}, leave the carrier's group, and resume discovery for
 *       other carriers (each carrier is tried at most once per period).</li>
 * </ol>
 * A failed attempt (connect error/timeout, became GO, wrong group) just leaves that carrier's group and
 * keeps discovering others until {@link #stop}; it does not produce {@code visitFinished} because no
 * exchange ran.
 *
 * <p>Threading: main thread only, except the {@link VisitRunner}. Generation guard: a {@link Session} per
 * {@code startVisit} and an {@link Attempt} per carrier; every continuation checks they are still live.
 */
@SuppressLint("MissingPermission") // P2pLink.precheck verifies permissions; SecurityException is caught per call
final class DropboxWifiJoiner {
    private static final Logger logger = Logger.getLogger(DropboxWifiJoiner.class.getName());

    /** Joiner lifecycle as seen by the UI. */
    enum Phase { IDLE, STARTING, DISCOVERING, CONNECTING, EXCHANGING, DISCONNECTING, STOPPING }

    private final P2pLink link;
    private final VisitRunner runner;
    private final Runnable stateChanged;
    private final CarrierSelector selector = new CarrierSelector();

    private Phase phase = Phase.IDLE;
    private Session active;
    /** Carrier group we are currently in (client side), for the UI. */
    private WifiP2pGroup carrierGroup;
    private InetAddress carrierAddress;

    /**
     * @param link         shared channel and helpers
     * @param runner       the carrier exchange (integrator's TransportVisitClient)
     * @param stateChanged called after any UI-visible change
     */
    DropboxWifiJoiner(P2pLink link, VisitRunner runner, Runnable stateChanged) {
        this.link = link;
        this.runner = runner;
        this.stateChanged = stateChanged;
    }

    Phase phase() {
        return phase;
    }

    boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** @return carrier being joined/exchanged with, or null */
    DiscoveredCarrier currentCarrier() {
        Session s = active;
        return s == null || s.attempt == null ? null : s.attempt.carrier;
    }

    /** @return carriers seen in this visit period */
    List<DiscoveredCarrier> discoveredCarriers() {
        return selector.snapshot();
    }

    /** @return UI view of the carrier's group while joined, or null */
    WifiRadioState.GroupInfo groupInfo() {
        WifiP2pGroup g = carrierGroup;
        if (g == null || phase == Phase.IDLE) return null;
        return new WifiRadioState.GroupInfo(g.getNetworkName(),
                                            null,
                                            carrierAddress == null ? null : carrierAddress.getHostAddress(),
                                            false,
                                            List.of());
    }

    // ---------------------------------------------------------------- start

    /** RadioDriver.startVisit, on the main thread. */
    void start(long gen, VisitCallback cb) {
        if (active != null) {
            // The controller never overlaps visits; if it does, the newer one wins.
            active.abandon("superseded by startVisit gen " + gen);
        }
        String problem = link.precheck(true);
        if (problem != null) {
            cb.failed(gen, problem);
            return;
        }
        Session s = new Session(gen, cb);
        active = s;
        selector.beginPeriod();
        carrierGroup = null;
        carrierAddress = null;
        phase = Phase.STARTING;
        stateChanged.run();
        // Step 1: stop advertising and leave any group (our own hosted group, or a stale carrier group).
        link.action("clearLocalServices", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.clearLocalServices(c, l), s::live, (cleared, why) -> {
            if (!s.live()) return;
            if (!cleared) logger.warning(why);
            link.removeGroupAndAwaitGone(s::live, (ok, whyNot) -> {
                if (!s.live()) return;
                if (ok) registerServiceRequest(s);
                else s.failStart("could not leave current group: " + whyNot);
            });
        });
    }

    /** Step 2: TXT listener + service request (as DDDWifiDirect.initialize does). */
    private void registerServiceRequest(Session s) {
        WifiP2pManager.Channel ch = link.channel();
        if (ch == null) {
            s.failStart("Wi-Fi Direct channel lost");
            return;
        }
        // The listener stays installed across sessions; it checks the active session itself.
        link.manager.setDnsSdResponseListeners(ch, null, this::onTxtRecord);
        WifiP2pDnsSdServiceRequest request =
                WifiP2pDnsSdServiceRequest.newInstance(DropboxTxt.INSTANCE_NAME, DropboxTxt.SERVICE_TYPE);
        link.action("clearServiceRequests", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.clearServiceRequests(c, l), s::live, (cleared, why) -> {
            if (!s.live()) return;
            if (!cleared) logger.warning(why);
            link.action("addServiceRequest", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.addServiceRequest(c, request, l), s::live, (ok, whyNot) -> {
                if (!s.live()) return;
                if (ok) discover(s);
                else s.failStart(whyNot);
            });
        });
    }

    /** Step 3: (re)start DNS-SD discovery. The first success reports ready. */
    private void discover(Session s) {
        link.action("discoverServices", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.discoverServices(c, l), s::live, (ok, why) -> {
            if (!s.live()) return;
            if (!ok) {
                if (!s.readyReported) {
                    s.failStart(why);
                    return;
                }
                logger.warning(why + " (will retry)");
            } else if (!s.readyReported) {
                s.readyReported = true;
                phase = Phase.DISCOVERING;
                link.wifi(INFO, "Searching for transports");
                stateChanged.run();
                s.cb.ready(s.gen);
            }
            scheduleRediscover(s);
            maybeConnect(s);
        });
    }

    private void scheduleRediscover(Session s) {
        link.main.removeCallbacks(s.rediscover);
        link.main.postDelayed(s.rediscover, WifiTimeouts.REDISCOVER_INTERVAL_MS);
    }

    /** DnsSdTxtRecordListener (main thread). */
    private void onTxtRecord(String fullDomainName, Map<String, String> txt, WifiP2pDevice device) {
        Session s = active;
        if (s == null || !s.live() || device == null) return;
        DropboxTxt.CarrierAd ad = DropboxTxt.parseCarrier(txt);
        if (!ad.isAccepted()) {
            logger.log(FINE, "Ignoring " + device.deviceName + " " + device.deviceAddress + ": " + ad.rejection());
            return;
        }
        if (selector.observe(ad.transportId(), device.deviceAddress, device.deviceName, ad.recencyBlobTimestamp(), P2pLink.now())) {
            link.wifi(INFO, "Found transport " + device.deviceName + " (" + ad.transportId() + ")");
        }
        stateChanged.run();
        maybeConnect(s);
    }

    // ---------------------------------------------------------------- connect

    private void maybeConnect(Session s) {
        if (!s.live() || phase != Phase.DISCOVERING || s.attempt != null) return;
        DiscoveredCarrier carrier = selector.next(P2pLink.now());
        if (carrier != null) connect(s, carrier);
    }

    /** Step 4: join the carrier's group as a client. */
    private void connect(Session s, DiscoveredCarrier carrier) {
        Attempt a = new Attempt(s, carrier);
        s.attempt = a;
        selector.markAttempted(carrier.carrierId());
        link.main.removeCallbacks(s.rediscover);
        phase = Phase.CONNECTING;
        link.wifi(INFO, "Connecting to transport " + carrier.label());
        stateChanged.run();

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = carrier.deviceAddress();
        // 0 = strongest preference for being the client: the carrier must stay GO for its own clients,
        // and if we became GO the carrier could not reach our gRPC peer anyway (we have no server running).
        config.groupOwnerIntent = 0;
        // FUTURE: fallback join by credentials (WifiP2pConfig.Builder network "DIRECT-ddd_" + carrierId,
        // passphrase "ConnectToMe") without GO negotiation, if deviceAddress connects prove flaky (R2 §1.3 item 3).

        // One deadline covers the request and the wait for the GO address.
        link.main.postDelayed(a.timeout, WifiTimeouts.CONNECT_MS);
        link.action("connect", WifiTimeouts.CONNECT_MS, (m, c, l) -> m.connect(c, config, l), a::live, (ok, why) -> {
            if (!a.live()) return;
            if (!ok) {
                attemptFailed(a, why);
                return;
            }
            pollConnectionInfo(a);
        });
    }

    /** Fallback for missed CONNECTION_CHANGED broadcasts (R2 §1.3 item 4). */
    private void pollConnectionInfo(Attempt a) {
        if (!a.live() || a.joined) return;
        WifiP2pManager.Channel ch = link.channel();
        if (ch == null) {
            attemptFailed(a, "Wi-Fi Direct channel lost while connecting");
            return;
        }
        try {
            link.manager.requestConnectionInfo(ch, info -> {
                if (!a.live() || a.joined) return;
                evaluateConnection(a, info, null);
                if (a.live() && !a.joined) link.main.postDelayed(() -> pollConnectionInfo(a), 1_000L);
            });
        } catch (SecurityException e) {
            attemptFailed(a, "requestConnectionInfo: permission denied (" + e.getMessage() + ")");
        }
    }

    /**
     * WIFI_P2P_CONNECTION_CHANGED_ACTION (from the driver's receiver).
     */
    void onConnectionChanged(WifiP2pInfo info, WifiP2pGroup group) {
        Session s = active;
        if (s != null && s.attempt != null && s.attempt.live() && !s.attempt.joined) {
            evaluateConnection(s.attempt, info, group);
        }
        if (group != null && !group.isGroupOwner() && isActive()) {
            carrierGroup = group;
        } else if (group == null) {
            carrierGroup = null;
        }
        stateChanged.run();
    }

    /**
     * Decides whether a connection report means we joined the carrier. Mirrors the client's checks
     * (DDDWifiDirect.java:94-115, 329-354) plus "we must not be GO".
     */
    private void evaluateConnection(Attempt a, WifiP2pInfo info, WifiP2pGroup group) {
        if (info == null || !info.groupFormed || info.groupOwnerAddress == null) return; // keep waiting
        if (info.isGroupOwner) {
            attemptFailed(a, "became group owner instead of joining " + a.carrier.label());
            return;
        }
        if (group != null && group.getOwner() != null && group.getOwner().deviceAddress != null &&
                !group.getOwner().deviceAddress.equalsIgnoreCase(a.carrier.deviceAddress())) {
            attemptFailed(a, "joined " + group.getOwner().deviceName + " instead of " + a.carrier.label());
            return;
        }
        if (group != null) carrierGroup = group;
        onJoined(a, info.groupOwnerAddress);
    }

    // ---------------------------------------------------------------- exchange

    /** Step 5: hand the carrier to the VisitRunner on a worker thread. */
    private void onJoined(Attempt a, InetAddress goAddress) {
        a.joined = true;
        link.main.removeCallbacks(a.timeout);
        carrierAddress = goAddress;
        phase = Phase.EXCHANGING;
        link.wifi(INFO, "Joined " + a.carrier.label() + " at " + goAddress.getHostAddress());
        stateChanged.run();
        Session s = a.session;
        String carrierId = a.carrier.carrierId();
        try {
            a.runner = link.worker().submit(() -> {
                boolean ok = false;
                String error = null;
                try {
                    ok = runner.runVisit(goAddress, carrierId, s.stopFlag::get);
                } catch (Throwable t) {
                    error = t.toString();
                    logger.log(WARNING, "visit to " + carrierId + " threw", t);
                }
                // Set before posting: stopVisit may be waiting for it (Future.isDone() is already true
                // after cancel(true), so it cannot tell whether the thread really returned).
                a.runnerExited.set(true);
                boolean success = ok;
                String err = error;
                link.main.post(() -> onExchangeDone(a, success, err));
            });
        } catch (RuntimeException e) { // RejectedExecutionException after shutdown
            attemptFailed(a, "cannot start exchange: " + e);
        }
    }

    /** Step 6: report, leave the carrier, keep looking. */
    private void onExchangeDone(Attempt a, boolean success, String error) {
        if (!a.live()) return; // stopVisit already took over (it waits for the runner itself)
        a.done = true;
        Session s = a.session;
        selector.recordVisit(a.carrier.carrierId(), P2pLink.now(), success);
        link.ui(UserLogRepository.UserLogType.EXCHANGE,
                success ? INFO : WARNING,
                "Visit to " + a.carrier.label() + (success ? " finished" : " failed" + (error == null ? "" : ": " + error)));
        s.cb.visitFinished(s.gen, success);
        leaveCarrier(a);
    }

    private void attemptFailed(Attempt a, String reason) {
        if (!a.live()) return;
        a.done = true;
        link.main.removeCallbacks(a.timeout);
        link.wifi(WARNING, "Could not join " + a.carrier.label() + ": " + reason);
        leaveCarrier(a);
    }

    /** cancelConnect + removeGroup, then resume discovery for other carriers. */
    private void leaveCarrier(Attempt a) {
        Session s = a.session;
        if (!s.live()) return;
        phase = Phase.DISCONNECTING;
        stateChanged.run();
        link.action("cancelConnect", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.cancelConnect(c, l), s::live, (ignored, why) ->
                link.removeGroupAndAwaitGone(s::live, (ok, whyNot) -> {
                    if (!s.live()) return;
                    if (!ok) logger.warning("leaving carrier group: " + whyNot);
                    carrierGroup = null;
                    carrierAddress = null;
                    s.attempt = null;
                    phase = Phase.DISCOVERING;
                    stateChanged.run();
                    discover(s); // also tries the next untried carrier
                }));
    }

    // ---------------------------------------------------------------- stop

    /**
     * RadioDriver.stopVisit, on the main thread: cancel connect/exchange, leave the carrier's group, stop
     * discovery, wait (bounded) for the runner to return, then report ready.
     */
    void stop(long gen, RadioCallback cb) {
        Session s = active;
        Attempt exchanging = s != null && s.attempt != null && s.attempt.runner != null ? s.attempt : null;
        if (s != null) s.abandon("cancelled by stopVisit gen " + gen);
        phase = Phase.STOPPING;
        stateChanged.run();
        // Interrupt the exchange (it also sees stopFlag); leaving the group below breaks its sockets.
        if (exchanging != null) exchanging.runner.cancel(true);
        if (link.manager == null || link.channel() == null) {
            finishStop(gen, cb, exchanging);
            return;
        }
        // Each step is best effort (failures are expected, e.g. cancelConnect with nothing pending), except
        // the final group-removal observation, which decides ready vs failed.
        link.action("cancelConnect", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.cancelConnect(c, l), () -> true, (ok1, why1) ->
                link.action("stopPeerDiscovery", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.stopPeerDiscovery(c, l), () -> true, (ok2, why2) ->
                        link.action("clearServiceRequests", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.clearServiceRequests(c, l), () -> true, (ok3, why3) ->
                                link.removeGroupAndAwaitGone(() -> true, (ok, why) -> {
                                    if (ok) {
                                        finishStop(gen, cb, exchanging);
                                        return;
                                    }
                                    phase = Phase.IDLE;
                                    stateChanged.run();
                                    link.wifi(SEVERE, "Could not leave carrier group: " + why);
                                    cb.failed(gen, why);
                                }))));
    }

    /** Waits up to {@link WifiTimeouts#RUNNER_EXIT_MS} for a cancelled runner, then reports idle. */
    private void finishStop(long gen, RadioCallback cb, Attempt exchanging) {
        long deadline = P2pLink.now() + WifiTimeouts.RUNNER_EXIT_MS;
        Runnable check = new Runnable() {
            @Override
            public void run() {
                boolean running = exchanging != null && !exchanging.runnerExited.get();
                if (running && P2pLink.now() < deadline) {
                    link.main.postDelayed(this, 100L);
                    return;
                }
                if (running) {
                    logger.warning("visit runner did not exit within " + WifiTimeouts.RUNNER_EXIT_MS + " ms");
                }
                carrierGroup = null;
                carrierAddress = null;
                phase = Phase.IDLE;
                stateChanged.run();
                cb.ready(gen);
            }
        };
        check.run();
    }

    /** Wi-Fi Direct disabled: fail a pending start now; an ongoing visit ends at the controller's stopVisit. */
    void onP2pDisabled() {
        Session s = active;
        if (s != null && !s.readyReported) s.failStart("Wi-Fi Direct was disabled");
        else if (s != null && s.attempt != null) attemptFailed(s.attempt, "Wi-Fi Direct was disabled");
    }

    // ---------------------------------------------------------------- session / attempt guards

    /** One startVisit..stopVisit period. */
    private final class Session {
        final long gen;
        final VisitCallback cb;
        final AtomicBoolean stopFlag = new AtomicBoolean(false); // read by the runner thread
        final Runnable rediscover;
        boolean readyReported;
        boolean ended;
        Attempt attempt;

        Session(long gen, VisitCallback cb) {
            this.gen = gen;
            this.cb = cb;
            this.rediscover = () -> {
                if (live() && phase == Phase.DISCOVERING && attempt == null) discover(this);
            };
        }

        boolean live() {
            return !ended && active == this;
        }

        /** startVisit failed before ready: report failed once and release the session. */
        void failStart(String reason) {
            if (ended) return;
            end();
            phase = Phase.IDLE;
            link.wifi(SEVERE, "Could not start transport search: " + reason);
            stateChanged.run();
            cb.failed(gen, reason);
            // Best effort: do not leave a half-started discovery running.
            link.action("stopPeerDiscovery", WifiTimeouts.SERVICE_OP_MS, (m, c, l) -> m.stopPeerDiscovery(c, l), () -> true, (ok, why) -> {});
        }

        /** Ends the session without a further start callback, except a pending start, which fails. */
        void abandon(String reason) {
            if (ended) return;
            boolean wasReady = readyReported;
            end();
            if (!wasReady) cb.failed(gen, reason); // keep "exactly one of ready/failed" for the start op
        }

        private void end() {
            ended = true;
            stopFlag.set(true);
            link.main.removeCallbacks(rediscover);
            if (attempt != null) {
                attempt.done = true;
                link.main.removeCallbacks(attempt.timeout);
            }
            if (active == this) active = null;
        }
    }

    /** One try at one carrier within a session. */
    private final class Attempt {
        final Session session;
        final DiscoveredCarrier carrier;
        final Runnable timeout;
        /** Set by the worker thread when {@link VisitRunner#runVisit} has returned. */
        final AtomicBoolean runnerExited = new AtomicBoolean(false);
        boolean joined;
        boolean done;
        Future<?> runner;

        Attempt(Session session, DiscoveredCarrier carrier) {
            this.session = session;
            this.carrier = carrier;
            this.timeout = () -> attemptFailed(this, "no group-owner address within " + WifiTimeouts.CONNECT_MS + " ms");
        }

        boolean live() {
            return !done && session.live() && session.attempt == this;
        }
    }
}
