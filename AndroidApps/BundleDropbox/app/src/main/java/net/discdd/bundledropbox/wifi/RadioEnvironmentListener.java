package net.discdd.bundledropbox.wifi;

/**
 * Notified when the radio's environment changes in a way the role cycle should react to.
 *
 * <p>03 Architecture: after 3 failed attempts the controller goes UNAVAILABLE and "retries after 30 s or
 * when Wi-Fi/permission state changes". The {@code RadioDriver} callbacks have no channel for that, so the
 * driver reports it here; the service calls {@code RoleCycleController.retryNow()} when {@code available}
 * becomes true and {@code roleLost(reason)} when it becomes false.
 *
 * <p>Two kinds of event, kept apart (audit findings m3/m10):
 * <ul>
 *   <li>{@link #onRadioAvailabilityChanged}: <b>edges only</b> of the real environment (Wi-Fi Direct
 *       enabled/disabled, permission granted, P2P channel lost/re-created). An "unavailable" edge implies
 *       the current role is lost; no separate {@link #onRoleLost} follows for the same event.</li>
 *   <li>{@link #onRoleLost}: the radio dropped the current role by itself while staying usable, e.g. the
 *       hosted group vanished while HOSTING, which the {@code RadioDriver} callbacks cannot express after
 *       {@code ready}. It is not an availability change and does not affect edge detection.</li>
 * </ul>
 *
 * <p>Called on the main thread.
 */
@FunctionalInterface
public interface RadioEnvironmentListener {
    /**
     * @param available true when Wi-Fi Direct is enabled, the channel is up and permissions are granted
     * @param reason    human-readable cause, e.g. "Wi-Fi Direct enabled", "permission granted",
     *                  "Wi-Fi Direct channel lost"
     */
    void onRadioAvailabilityChanged(boolean available, String reason);

    /**
     * The current role was lost while the radio itself stays available (e.g. "hosted group lost").
     *
     * <p>The default forwards to {@code onRadioAvailabilityChanged(false, reason)} so a single-lambda
     * listener (which maps "not available" to {@code roleLost}) keeps working. That forward is only a
     * notification: it does not change the driver's edge state, so the next real edge is still reported.
     *
     * @param reason human-readable cause
     */
    default void onRoleLost(String reason) {
        onRadioAvailabilityChanged(false, reason);
    }
}
