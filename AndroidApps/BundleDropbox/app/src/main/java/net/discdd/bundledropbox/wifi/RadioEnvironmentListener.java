package net.discdd.bundledropbox.wifi;

/**
 * Notified when the radio's environment changes in a way the role cycle should react to.
 *
 * <p>03 Architecture: after 3 failed attempts the controller goes UNAVAILABLE and "retries after 30 s or
 * when Wi-Fi/permission state changes". The {@code RadioDriver} contract has no channel for that, so the
 * driver reports it here; the integrator typically nudges the controller (e.g. resume/restart) when
 * {@code available} becomes true. It also reports a hosted group that disappears while HOSTING (e.g. the
 * user turned Wi-Fi off), which the {@code RadioDriver} callbacks cannot express after {@code ready}.
 *
 * <p>Called on the main thread.
 */
@FunctionalInterface
public interface RadioEnvironmentListener {
    /**
     * @param available true when Wi-Fi Direct is enabled and permissions are granted (radio usable)
     * @param reason    human-readable cause, e.g. "Wi-Fi Direct enabled", "permission granted",
     *                  "hosted group lost"
     */
    void onRadioAvailabilityChanged(boolean available, String reason);
}
