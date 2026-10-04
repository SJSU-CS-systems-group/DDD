package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.wifi.RadioEnvironmentListener;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Turns radio-environment changes into role-cycle nudges (Review #10). Was an inline lambda in
 * {@code DropboxService}; extracted so the rule is JVM-tested (audit m19).
 * <ul>
 *   <li><b>Available</b> (Wi-Fi Direct enabled, channel up, permission granted): {@code retryNow}, so
 *       UNAVAILABLE does not wait out its 30 s retry timer (03 Architecture: "retries after 30 s or when
 *       Wi-Fi/permission state changes"). The controller ignores it in any other mode.</li>
 *   <li><b>Not available</b> (P2P disabled, channel lost) and <b>role lost</b> (hosted group gone while the
 *       radio stays usable, {@link #onRoleLost}): {@code roleLost}, which ends the current dwell at once
 *       instead of "serving clients" with no group until the timer ends.</li>
 * </ul>
 * Runs on the main thread (driver callback); both nudges only post to the cycle thread.
 */
public final class CycleEnvironmentBridge implements RadioEnvironmentListener {
    private final Runnable retryNow;
    private final Consumer<String> roleLost;
    private final BiConsumer<Boolean, String> log;

    /**
     * @param retryNow {@code RoleCycleController::retryNow}
     * @param roleLost {@code RoleCycleController::roleLost}
     * @param log      operator log line (available, reason)
     */
    public CycleEnvironmentBridge(Runnable retryNow, Consumer<String> roleLost, BiConsumer<Boolean, String> log) {
        this.retryNow = Objects.requireNonNull(retryNow, "retryNow");
        this.roleLost = Objects.requireNonNull(roleLost, "roleLost");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void onRadioAvailabilityChanged(boolean available, String reason) {
        try {
            log.accept(available, reason);
        } finally { // a failing log line must not swallow the nudge
            if (available) retryNow.run();
            else roleLost.accept(reason);
        }
    }

    /** The role was lost while the radio stays usable (e.g. hosted group gone): end the dwell now. */
    @Override
    public void onRoleLost(String reason) {
        try {
            log.accept(false, reason);
        } finally {
            roleLost.accept(reason);
        }
    }
}
