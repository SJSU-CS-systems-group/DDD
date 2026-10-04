package net.discdd.bundledropbox.service;

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
     * Whether the partial wake lock should be held (audit M1/M2/M3: the cycle's executor timers and the Wi-Fi
     * layer's {@code Handler.postDelayed} timeouts stop counting while the CPU is suspended, so with the screen
     * off a dwell could last many times its period). Held while the relay is cycling: on by default, released
     * while paused or stopped (no timers run then) and after destroy.
     */
    public static boolean wantsWakeLock(boolean keepAwakeSetting, boolean paused, boolean operatorStopped,
                                        boolean destroyed) {
        return keepAwakeSetting && !paused && !operatorStopped && !destroyed;
    }
}
