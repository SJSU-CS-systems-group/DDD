package net.discdd.bundledropbox.wifi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import net.discdd.utils.LogUtil;
import net.discdd.utils.UserLogRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@code WifiP2pManager.Channel} shared by {@link DropboxWifiHost} and {@link DropboxWifiJoiner},
 * plus the plumbing both need: permission/availability checks, bounded async calls with BUSY retry,
 * group polling, a worker pool for blocking work, and UI logging.
 *
 * <p>Why one channel: a phone has one P2P interface that is either a group owner or a client, never
 * both, and the dropbox alternates between the two roles (Research/R2 §1.3 item 1). Two channels would let
 * the transport-style and client-style code race each other on the same interface.
 *
 * <p>Threading: the channel is initialized on the main looper, so every {@code WifiP2pManager} callback
 * arrives on the main thread. All methods here must be called on the main thread (the driver posts every
 * public call to {@link #main}); only {@link #runOnWorker} hops to a background thread and back.
 *
 * <p>Every async helper reports its {@link Result} exactly once, including on timeout or abandonment, so
 * callers can chain steps without leaks; callers still check their own generation guard on each result.
 */
@SuppressLint("MissingPermission") // permissions are checked in precheck(); SecurityException is caught per call
final class P2pLink {
    private static final Logger logger = Logger.getLogger(P2pLink.class.getName());

    /** A WifiP2pManager request taking an ActionListener. */
    interface P2pCall {
        void invoke(WifiP2pManager manager, WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener);
    }

    /** Blocking work run off the main thread. */
    interface BlockingTask {
        void run() throws Exception;
    }

    /** Completion of an async step: {@code failure} is null iff {@code ok}. */
    interface Result {
        void done(boolean ok, String failure);
    }

    final Context context;
    final Handler main;
    /** Null on devices without Wi-Fi Direct. */
    final WifiP2pManager manager;
    private final Runnable onChannelLost;
    private final ExecutorService worker;
    private WifiP2pManager.Channel channel;

    /** Last {@code EXTRA_WIFI_STATE}; -1 until known. */
    private int p2pState = -1;
    private String deviceName;
    private String deviceAddress;

    /**
     * @param context       application or service context
     * @param main          handler on the main looper
     * @param onChannelLost run (on main) when the framework disconnects the channel
     */
    P2pLink(Context context, Handler main, Runnable onChannelLost) {
        this.context = context;
        this.main = main;
        this.manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.onChannelLost = onChannelLost;
        AtomicInteger n = new AtomicInteger();
        this.worker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dropbox-wifi-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    // ---------------------------------------------------------------- channel

    /**
     * Creates the channel if needed (first use, or after the framework dropped it).
     *
     * @return true if a channel is available
     */
    boolean ensureChannel() {
        if (manager == null) return false;
        if (channel == null) {
            channel = manager.initialize(context, main.getLooper(), () -> {
                // ChannelListener runs on the channel looper (main).
                logger.warning("Wi-Fi P2P channel disconnected by the framework; will re-initialize on next use");
                channel = null;
                onChannelLost.run();
            });
            if (channel == null) logger.severe("WifiP2pManager.initialize returned null");
        }
        return channel != null;
    }

    /** @return current channel, or null */
    WifiP2pManager.Channel channel() {
        return channel;
    }

    /** Closes the channel and stops the worker pool. */
    void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- availability

    /** @return true if NEARBY_WIFI_DEVICES is granted (required for every P2P call on API 33+) */
    boolean hasNearbyPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED;
    }

    void setP2pState(int state) {
        p2pState = state;
    }

    /** @return true unless the system told us Wi-Fi Direct is disabled (unknown counts as enabled) */
    boolean p2pEnabled() {
        return p2pState != WifiP2pManager.WIFI_P2P_STATE_DISABLED;
    }

    /** @return true only if the system confirmed Wi-Fi Direct is enabled */
    boolean p2pConfirmedEnabled() {
        return p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
    }

    void setDevice(String name, String address) {
        deviceName = name;
        deviceAddress = address;
    }

    String deviceName() {
        return deviceName;
    }

    String deviceAddress() {
        return deviceAddress;
    }

    /**
     * Checks everything an operation needs before touching the radio. Both roles need only
     * NEARBY_WIFI_DEVICES: on minSdk 33 it is declared {@code neverForLocation} (android-core manifest), which
     * covers DNS-SD discovery and connect without location. The stock BundleClient runs the same discovery and
     * connect with only NEARBY requested at runtime (its {@code HomeScreen}); the
     * {@code @RequiresPermission(ACCESS_FINE_LOCATION)} on {@code DDDWifiDirect} is a lint annotation only.
     * Requiring FINE here used to block every VISIT, and with it the bootstrap, on a permission the UI could
     * not even obtain on API 31+ (a FINE request without COARSE is dropped by the system).
     *
     * @param forVisit true for VISIT, false for HOST (currently the same requirements; kept for clarity)
     * @return null if OK, otherwise a clear reason for {@code RadioCallback.failed}
     */
    String precheck(boolean forVisit) {
        if (manager == null) return "Wi-Fi Direct is not supported on this device";
        if (!hasNearbyPermission()) return "NEARBY_WIFI_DEVICES permission not granted";
        if (!p2pEnabled()) return "Wi-Fi Direct is disabled (turn on Wi-Fi)";
        if (!ensureChannel()) return "could not initialize the Wi-Fi Direct channel";
        return null;
    }

    // ---------------------------------------------------------------- async helpers

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Issues a WifiP2pManager request, retrying BUSY with {@link RetryBackoff#BUSY} inside
     * {@code timeoutMs}. Other failure codes, a SecurityException, a lost channel, the timeout, or
     * {@code stillWanted} turning false complete the step with ok=false.
     */
    void action(String what, long timeoutMs, P2pCall call, BooleanSupplier stillWanted, Result result) {
        new PendingAction(what, timeoutMs, call, stillWanted, result).start();
    }

    /** One in-flight {@link #action} with its retry state. */
    private final class PendingAction {
        private final String what;
        private final long timeoutMs;
        private final long deadline;
        private final P2pCall call;
        private final BooleanSupplier stillWanted;
        private final Result result;
        private final Runnable timeout;
        private boolean finished;

        PendingAction(String what, long timeoutMs, P2pCall call, BooleanSupplier stillWanted, Result result) {
            this.what = what;
            this.timeoutMs = timeoutMs;
            this.deadline = now() + timeoutMs;
            this.call = call;
            this.stillWanted = stillWanted;
            this.result = result;
            this.timeout = () -> finish(false, what + " timed out after " + timeoutMs + " ms");
        }

        void start() {
            main.postDelayed(timeout, timeoutMs);
            attempt(0);
        }

        private void attempt(int retryIndex) {
            if (finished) return;
            if (!stillWanted.getAsBoolean()) {
                finish(false, what + " abandoned");
                return;
            }
            WifiP2pManager.Channel ch = channel;
            if (manager == null || ch == null) {
                finish(false, what + ": Wi-Fi Direct channel not available");
                return;
            }
            try {
                call.invoke(manager, ch, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        finish(true, null);
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (finished) return;
                        if (reason == P2pReasons.BUSY) {
                            long delay = RetryBackoff.BUSY.delayBeforeRetry(retryIndex);
                            if (delay >= 0 && now() + delay < deadline) {
                                logger.fine(what + " BUSY, retry " + (retryIndex + 1) + " in " + delay + " ms");
                                main.postDelayed(() -> attempt(retryIndex + 1), delay);
                                return;
                            }
                        }
                        finish(false, what + " failed: " + P2pReasons.describe(reason));
                    }
                });
            } catch (SecurityException e) {
                finish(false, what + ": permission denied (" + e.getMessage() + ")");
            } catch (RuntimeException e) {
                finish(false, what + " threw " + e);
            }
        }

        private void finish(boolean ok, String failure) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(timeout);
            result.done(ok, failure);
        }
    }

    /**
     * Polls {@code requestGroupInfo} every {@link WifiTimeouts#POLL_INTERVAL_MS} until {@code until}
     * accepts the group (which may be null), {@code timeoutMs} passes, or {@code stillWanted} turns false.
     * A watchdog covers a framework that never answers.
     *
     * @param onMatch called with the accepted group
     * @param onFail  called with a reason on timeout, abandonment, lost channel or SecurityException
     */
    void pollGroup(String what,
                   long timeoutMs,
                   Predicate<WifiP2pGroup> until,
                   BooleanSupplier stillWanted,
                   Consumer<WifiP2pGroup> onMatch,
                   Consumer<String> onFail) {
        new GroupPoll(what, timeoutMs, until, stillWanted, onMatch, onFail).start();
    }

    private final class GroupPoll implements Runnable {
        private final String what;
        private final long timeoutMs;
        private final long deadline;
        private final Predicate<WifiP2pGroup> until;
        private final BooleanSupplier stillWanted;
        private final Consumer<WifiP2pGroup> onMatch;
        private final Consumer<String> onFail;
        private final Runnable watchdog;
        private boolean finished;

        GroupPoll(String what,
                  long timeoutMs,
                  Predicate<WifiP2pGroup> until,
                  BooleanSupplier stillWanted,
                  Consumer<WifiP2pGroup> onMatch,
                  Consumer<String> onFail) {
            this.what = what;
            this.timeoutMs = timeoutMs;
            this.deadline = now() + timeoutMs;
            this.until = until;
            this.stillWanted = stillWanted;
            this.onMatch = onMatch;
            this.onFail = onFail;
            // Fires one interval after the deadline in case the last requestGroupInfo never answers.
            this.watchdog = () -> fail(what + " timed out after " + timeoutMs + " ms (no answer)");
        }

        void start() {
            main.postDelayed(watchdog, timeoutMs + WifiTimeouts.POLL_INTERVAL_MS);
            run();
        }

        @Override
        public void run() {
            if (finished) return;
            if (!stillWanted.getAsBoolean()) {
                fail(what + " abandoned");
                return;
            }
            WifiP2pManager.Channel ch = channel;
            if (manager == null || ch == null) {
                fail(what + ": Wi-Fi Direct channel not available");
                return;
            }
            try {
                manager.requestGroupInfo(ch, group -> {
                    if (finished) return;
                    if (until.test(group)) {
                        finished = true;
                        main.removeCallbacks(watchdog);
                        onMatch.accept(group);
                    } else if (now() >= deadline) {
                        fail(what + " timed out after " + timeoutMs + " ms");
                    } else {
                        main.postDelayed(this, WifiTimeouts.POLL_INTERVAL_MS);
                    }
                });
            } catch (SecurityException e) {
                fail(what + ": permission denied (" + e.getMessage() + ")");
            }
        }

        private void fail(String reason) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(watchdog);
            main.removeCallbacks(this);
            onFail.accept(reason);
        }
    }

    /**
     * Leaves whatever group this device is in (as GO or client) and waits until {@code requestGroupInfo}
     * returns null. A failed removeGroup request is not fatal: the framework answers ERROR when there is
     * no group, so the outcome is decided by observing the group, bounded by {@link WifiTimeouts#REMOVAL_MS}.
     */
    void removeGroupAndAwaitGone(BooleanSupplier stillWanted, Result result) {
        long deadline = now() + WifiTimeouts.REMOVAL_MS;
        action("removeGroup", WifiTimeouts.REMOVAL_MS, (m, c, l) -> m.removeGroup(c, l), stillWanted, (ok, why) -> {
            if (!ok) logger.fine(why + " (checking whether a group is still present)");
            long remaining = Math.max(WifiTimeouts.POLL_INTERVAL_MS, deadline - now());
            pollGroup("waiting for group removal",
                      remaining,
                      g -> g == null,
                      stillWanted,
                      g -> result.done(true, null),
                      reason -> result.done(false, ok ? reason : why + "; " + reason));
        });
    }

    /**
     * Runs blocking work on the worker pool and reports back on the main thread, bounded by
     * {@code timeoutMs} (on timeout the work keeps running but its late result is dropped).
     */
    void runOnWorker(String what, long timeoutMs, BlockingTask task, Result result) {
        boolean[] finished = {false}; // main-thread only
        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            result.done(false, what + " timed out after " + timeoutMs + " ms");
        };
        main.postDelayed(timeout, timeoutMs);
        try {
            worker.execute(() -> {
                String failure = null;
                try {
                    task.run();
                } catch (Throwable t) {
                    failure = what + " failed: " + t;
                    logger.log(Level.WARNING, failure, t);
                }
                String f = failure;
                main.post(() -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    main.removeCallbacks(timeout);
                    result.done(f == null, f);
                });
            });
        } catch (RuntimeException e) { // RejectedExecutionException after close()
            main.removeCallbacks(timeout);
            finished[0] = true;
            result.done(false, what + " not run: " + e);
        }
    }

    /** @return the worker pool, for long-running visit exchanges that have no timeout of their own */
    ExecutorService worker() {
        return worker;
    }

    // ---------------------------------------------------------------- logging

    /**
     * Logs to java.util.logging and to the user-visible log list (android-core {@code UserLogRepository}),
     * for messages that have no string resource. The dropbox's strings.xml is owned by the scaffold, so
     * new dropbox-only radio messages are plain English here.
     */
    void ui(UserLogRepository.UserLogType type, Level level, String message) {
        logger.log(level, type + ": " + message);
        UserLogRepository.INSTANCE.log(new UserLogRepository.UserLogEntry(type,
                                                                          System.currentTimeMillis(),
                                                                          message,
                                                                          level));
    }

    /** {@link #ui} for Wi-Fi messages. */
    void wifi(Level level, String message) {
        ui(UserLogRepository.UserLogType.WIFI, level, message);
    }

    /** Logs a localized transport string, exactly like the transport's {@code logWifi} (R1 §5). */
    void wifiRes(Level level, @StringRes int resId, Object... args) {
        LogUtil.logUi(context, logger, UserLogRepository.UserLogType.WIFI, level, resId, args);
    }
}
