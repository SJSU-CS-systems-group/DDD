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
 *       (polling {@code requestConnectionInfo} as fallback), and accept it per {@link JoinCheck}: we are
 *       <em>not</em> GO, and the GO is the carrier we asked for (the client's {@code sameAddressAs} check).</li>
 *   <li>Run the {@link VisitRunner} ({@code service/VisitCoordinator}, wrapping {@code TransportVisitClient})
 *       on a worker thread with (GO address, carrier id).</li>
 *   <li>Report {@code visitFinished(gen, success)}, leave the carrier's group, and resume discovery for
 *       other carriers (each carrier is tried at most once per period).</li>
 * </ol>
 * A failed attempt (connect error/timeout, became GO, wrong group) just leaves that carrier's group and
 * keeps discovering others until {@link #stop}; it does not produce {@code visitFinished} because no
 * exchange ran. Each attempt follows {@link AttemptLifecycle}: once joined it ends only when its runner
 * returns, so losing Wi-Fi Direct mid-exchange cancels the runner and still reports {@code visitFinished}.
 *
 * <p>Join hooks (audit m1): {@link VisitRunner#beginJoin} is called right before {@code connect}, so the
 * service holds the visit gate token for the whole join and a dwell that expires mid-join drains instead of
 * stopping at once. If it refuses (admissions already closed), no connect is issued and the joiner stays in
 * discovery until the controller stops the visit. {@link VisitRunner#endJoin} is called whenever the attempt
 * ends ({@link #leaveCarrier}, or the session ending); it is a no-op once the exchange took the token.
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
     * @param runner       the carrier exchange and its join hooks ({@code service/VisitCoordinator})
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
        link.bestEffort(s::live, () -> leaveGroupThenRegister(s),
                        P2pLink.step("clearLocalServices", P2pLink.CLEAR_LOCAL_SERVICES));
    }

    private void leaveGroupThenRegister(Session s) {
        link.removeGroupAndAwaitGone(s::live, (ok, whyNot) -> {
            if (!s.live()) return;
            if (ok) registerServiceRequest(s);
            else s.failStart("could not leave current group: " + whyNot);
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
        link.bestEffort(s::live, () -> addServiceRequest(s, request),
                        P2pLink.step("clearServiceRequests", P2pLink.CLEAR_SERVICE_REQUESTS));
    }

    private void addServiceRequest(Session s, WifiP2pDnsSdServiceRequest request) {
        P2pLink.P2pCall add = (m, c, l) -> m.addServiceRequest(c, request, l);
        link.action("addServiceRequest", WifiTimeouts.SERVICE_OP_MS, add, s::live, (ok, why) -> {
            if (!s.live()) return;
            if (ok) discover(s);
            else s.failStart(why);
        });
    }

    /** Step 3: (re)start DNS-SD discovery. The first success reports ready. */
    private void discover(Session s) {
        link.action("discoverServices", WifiTimeouts.SERVICE_OP_MS, P2pLink.DISCOVER_SERVICES, s::live, (ok, why) -> {
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
        long now = P2pLink.now();
        if (selector.observe(ad.transportId(), device.deviceAddress, device.deviceName, ad.recencyBlobTimestamp(), now)) {
            link.wifi(INFO, "Found transport " + device.deviceName + " (" + ad.transportId() + ")");
        }
        stateChanged.run();
        maybeConnect(s);
    }

    // ---------------------------------------------------------------- connect

    private void maybeConnect(Session s) {
        if (!s.live() || phase != Phase.DISCOVERING || s.attempt != null) return;
        DiscoveredCarrier carrier = selector.next(P2pLink.now());
        if (carrier == null) return;
        if (!runner.beginJoin(carrier.carrierId())) {
            // The visit period is ending (gate closed): joining now would be wasted. The carrier stays
            // untried, and the controller's stopVisit follows shortly.
            logger.log(FINE, "Not joining " + carrier.label() + ": the transport period is ending");
            return;
        }
        connect(s, carrier);
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
        // 0 = strongest preference for being the client: the carrier must stay GO for its own clients, and
        // the carrier's gRPC server at its GO address is what the visit talks to.
        config.groupOwnerIntent = 0;
        // FUTURE: fallback join by credentials (WifiP2pConfig.Builder network "DIRECT-ddd_" + carrierId,
        // passphrase "ConnectToMe") without GO negotiation, if deviceAddress connects prove flaky (R2 §1.3 item 3).

        // One deadline covers the request and the wait for the GO address.
        link.main.postDelayed(a.timeout, WifiTimeouts.CONNECT_MS);
        P2pLink.P2pCall connect = (m, c, l) -> m.connect(c, config, l);
        link.action("connect", WifiTimeouts.CONNECT_MS, connect, a::live, (ok, why) -> {
            if (!a.live()) return;
            if (ok) pollConnectionInfo(a);
            else attemptFailed(a, why);
        });
    }

    /** Fallback for missed CONNECTION_CHANGED broadcasts (R2 §1.3 item 4). */
    private void pollConnectionInfo(Attempt a) {
        if (!a.connecting()) return;
        WifiP2pManager.Channel ch = link.channel();
        if (ch == null) {
            attemptFailed(a, "Wi-Fi Direct channel lost while connecting");
            return;
        }
        try {
            link.manager.requestConnectionInfo(ch, info -> {
                if (!a.connecting()) return;
                evaluateConnection(a, info, null);
                if (a.connecting()) link.main.postDelayed(() -> pollConnectionInfo(a), WifiTimeouts.CONNECTION_POLL_MS);
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
        if (s != null && s.attempt != null && s.attempt.connecting()) {
            evaluateConnection(s.attempt, info, group);
        }
        if (group != null && !group.isGroupOwner() && isActive()) {
            carrierGroup = group;
        } else if (group == null) {
            carrierGroup = null;
        }
        stateChanged.run();
    }

    /** Applies {@link JoinCheck} to a connection report for a connecting attempt. */
    private void evaluateConnection(Attempt a, WifiP2pInfo info, WifiP2pGroup group) {
        if (info == null) return; // keep waiting
        WifiP2pDevice owner = group == null ? null : group.getOwner();
        String ownerAddress = owner == null ? null : owner.deviceAddress;
        switch (JoinCheck.evaluate(info.groupFormed,
                                   info.isGroupOwner,
                                   info.groupOwnerAddress != null,
                                   ownerAddress,
                                   a.carrier.deviceAddress())) {
            case WAIT -> {
            }
            case BECAME_GO -> attemptFailed(a, "became group owner instead of joining " + a.carrier.label());
            case WRONG_GO -> attemptFailed(a, "joined " + owner.deviceName + " instead of " + a.carrier.label());
            case JOINED -> {
                if (group != null) carrierGroup = group;
                onJoined(a, info.groupOwnerAddress);
            }
        }
    }

    // ---------------------------------------------------------------- exchange

    /** Step 5: hand the carrier to the VisitRunner on a worker thread. */
    private void onJoined(Attempt a, InetAddress goAddress) {
        if (!a.live() || !a.lifecycle.joined()) return;
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
                    ok = runner.runVisit(goAddress, carrierId, () -> s.stopFlag.get() || a.cancelled.get());
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
            onExchangeDone(a, false, "cannot start exchange: " + e);
        }
    }

    /** Step 6: report, leave the carrier, keep looking. */
    private void onExchangeDone(Attempt a, boolean success, String error) {
        // Not live: stopVisit already took over (it waits for the runner itself).
        if (!a.live() || !a.lifecycle.exchangeDone()) return;
        Session s = a.session;
        selector.recordVisit(a.carrier.carrierId(), P2pLink.now(), success);
        String outcome = success ? " finished" : " failed" + (error == null ? "" : ": " + error);
        link.ui(UserLogRepository.UserLogType.EXCHANGE, success ? INFO : WARNING, "Visit to " + a.carrier.label() + outcome);
        s.cb.visitFinished(s.gen, success);
        leaveCarrier(a);
    }

    /** A join failed (connect error, timeout, became GO, wrong GO): leave and keep discovering. */
    private void attemptFailed(Attempt a, String reason) {
        if (!a.live() || !a.lifecycle.failJoin()) return;
        abandonJoin(a, reason);
    }

    private void abandonJoin(Attempt a, String reason) {
        link.main.removeCallbacks(a.timeout);
        link.wifi(WARNING, "Could not join " + a.carrier.label() + ": " + reason);
        leaveCarrier(a);
    }

    /** cancelConnect + removeGroup, then resume discovery for other carriers. */
    private void leaveCarrier(Attempt a) {
        // Releases the join token if no exchange took it (failed join, or a runner that could not start).
        runner.endJoin(a.carrier.carrierId());
        Session s = a.session;
        if (!s.live()) return;
        phase = Phase.DISCONNECTING;
        stateChanged.run();
        link.bestEffort(s::live, () -> link.removeGroupAndAwaitGone(s::live, (ok, whyNot) -> onCarrierLeft(s, ok, whyNot)),
                        P2pLink.step("cancelConnect", P2pLink.CANCEL_CONNECT));
    }

    private void onCarrierLeft(Session s, boolean removed, String whyNot) {
        if (!s.live()) return;
        if (!removed) logger.warning("leaving carrier group: " + whyNot);
        carrierGroup = null;
        carrierAddress = null;
        s.attempt = null;
        phase = Phase.DISCOVERING;
        stateChanged.run();
        discover(s); // also tries the next untried carrier
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
        link.bestEffort(() -> true,
                        () -> link.removeGroupAndAwaitGone(() -> true, (ok, why) -> onStopRemoval(gen, cb, exchanging, ok, why)),
                        P2pLink.step("cancelConnect", P2pLink.CANCEL_CONNECT),
                        P2pLink.step("stopPeerDiscovery", P2pLink.STOP_PEER_DISCOVERY),
                        P2pLink.step("clearServiceRequests", P2pLink.CLEAR_SERVICE_REQUESTS));
    }

    private void onStopRemoval(long gen, RadioCallback cb, Attempt exchanging, boolean removed, String why) {
        if (removed) {
            finishStop(gen, cb, exchanging);
            return;
        }
        phase = Phase.IDLE;
        stateChanged.run();
        link.wifi(SEVERE, "Could not leave carrier group: " + why);
        cb.failed(gen, why);
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

    /**
     * Wi-Fi Direct disabled or channel lost. A pending start fails now. In an established visit, a connecting
     * attempt fails now, and an exchanging one has its runner cancelled; the runner's completion then reports
     * {@code visitFinished} and leaves the carrier as usual (see {@link AttemptLifecycle}).
     *
     * @return true if an established visit (ready already reported) was affected, i.e. the role was lost
     */
    boolean onRadioLost(String reason) {
        Session s = active;
        if (s == null) return false;
        if (!s.readyReported) {
            s.failStart(reason);
            return false;
        }
        Attempt a = s.attempt;
        if (a != null && a.live()) {
            switch (a.lifecycle.radioLost()) {
                case FAIL_NOW -> abandonJoin(a, reason);
                case CANCEL_EXCHANGE -> {
                    link.wifi(WARNING, "Cancelling visit to " + a.carrier.label() + ": " + reason);
                    a.cancelled.set(true);
                    if (a.runner != null) a.runner.cancel(true);
                }
                case NONE -> {
                }
            }
        }
        return true;
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
            link.bestEffort(() -> true, () -> { }, P2pLink.step("stopPeerDiscovery", P2pLink.STOP_PEER_DISCOVERY));
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
                attempt.lifecycle.end();
                link.main.removeCallbacks(attempt.timeout);
                // stopVisit, P2P disabled or a superseding start: a join that never reached the exchange
                // must not keep the visit token (the drain would then wait for the full drain cap).
                runner.endJoin(attempt.carrier.carrierId());
            }
            if (active == this) active = null;
        }
    }

    /** One try at one carrier within a session; its stages are an {@link AttemptLifecycle}. */
    private final class Attempt {
        final Session session;
        final DiscoveredCarrier carrier;
        final Runnable timeout;
        final AttemptLifecycle lifecycle = new AttemptLifecycle();
        /** Set by the worker thread when {@link VisitRunner#runVisit} has returned. */
        final AtomicBoolean runnerExited = new AtomicBoolean(false);
        /** Attempt-level stop request (radio lost) seen by the runner, besides the session's stopFlag. */
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<?> runner;

        Attempt(Session session, DiscoveredCarrier carrier) {
            this.session = session;
            this.carrier = carrier;
            this.timeout = () -> attemptFailed(this, "no group-owner address within " + WifiTimeouts.CONNECT_MS + " ms");
        }

        boolean live() {
            return !lifecycle.isDone() && session.live() && session.attempt == this;
        }

        /** @return true while live and still waiting to join */
        boolean connecting() {
            return live() && lifecycle.stage() == AttemptLifecycle.Stage.CONNECTING;
        }
    }
}
