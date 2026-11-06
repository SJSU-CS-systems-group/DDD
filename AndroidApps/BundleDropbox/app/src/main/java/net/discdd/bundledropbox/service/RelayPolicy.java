package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.cycle.CycleMode;

/**
 * Pure decisions of the service and the auto-start receiver, kept free of Android types for JVM tests.
 */
public final class RelayPolicy {
    /** {@code Intent.ACTION_BOOT_COMPLETED}. */
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    /** {@code Intent.ACTION_MY_PACKAGE_REPLACED}: this app was updated (the old process was killed). */
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";

    private RelayPolicy() {
    }

    /**
     * Whether a system broadcast should start the relay (audit m14, {@code restart-coverage-gaps}).
     *
     * <p>BOOT_COMPLETED covers reboots and power cuts. MY_PACKAGE_REPLACED covers app updates: Android does not
     * restart a {@code START_STICKY} service after its package is replaced, so without it the relay stayed down
     * until someone opened the app. Both broadcasts are exempt from the background foreground-service start
     * restrictions (Android 12+). LOCKED_BOOT_COMPLETED is deliberately not used: the store, the TLS keys and
     * the preferences all live in credential-encrypted storage, which is unreadable before the first unlock, so
     * a phone with a secure lock screen must be unlocked once after a reboot (see Fixes-service notes).
     *
     * @param action          the broadcast action
     * @param startOnBoot     the "Start automatically" setting
     * @param operatorStopped true if the operator pressed Stop (a stopped relay stays stopped)
     */
    public static boolean shouldAutoStart(String action, boolean startOnBoot, boolean operatorStopped) {
        boolean known = ACTION_BOOT_COMPLETED.equals(action) || ACTION_MY_PACKAGE_REPLACED.equals(action);
        return known && startOnBoot && !operatorStopped;
    }

    /**
     * Whether the partial wake lock should be held. Role deadlines use elapsed time and advance during sleep,
     * but callbacks wait for wake; Wi-Fi {@code Handler.postDelayed} timeouts exclude deep sleep. A dwell can
     * therefore exceed its period. Held while the relay is cycling: on by default, released
     * while paused or stopped (no timers run then) and after destroy.
     */
    public static boolean wantsWakeLock(boolean keepAwakeSetting, boolean paused, boolean operatorStopped,
                                        boolean destroyed) {
        return keepAwakeSetting && !paused && !operatorStopped && !destroyed;
    }

    /** Operator-facing words for a mode (notification and Logs; the Status tab has its own labels). */
    public static String describe(CycleMode mode) {
        return switch (mode) {
            case HOST_STARTING, HOSTING, HOST_DRAINING, HOST_STOPPING -> "serving clients";
            case VISIT_STARTING, VISITING, VISIT_DRAINING, VISIT_STOPPING -> "visiting transports";
            case UNAVAILABLE -> "radio unavailable, retrying";
            case STOPPED -> "stopped";
        };
    }

    /** @return the notification text for a role-cycle mode. */
    public static String roleNotificationText(CycleMode mode) {
        return "Currently " + describe(mode);
    }

    /**
     * The text a start command posts with {@code startForeground} (audit SW-3). Normally the last text, so a
     * re-delivered start keeps the role or failure text (m13). After an operator Stop the role updates were
     * suppressed, so the last text is the role from before the Stop; it is replaced by the STOPPED text, which
     * stays right while a persisted pause keeps the radio idle and until the next role update.
     *
     * @param last            the current notification text, or {@code null} for a new instance
     * @param initial         the text for a new instance (the default notification string)
     * @param operatorStopped true if this instance was stopped by the operator (role updates suppressed)
     */
    public static String startNotificationText(String last, String initial, boolean operatorStopped) {
        if (operatorStopped) return roleNotificationText(CycleMode.STOPPED);
        return last != null ? last : initial;
    }
}
