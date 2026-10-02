package net.discdd.bundledropbox.wifi;

/**
 * Observer of {@link WifiRadioState} changes, registered with
 * {@link WifiDirectRadioDriver#addStateListener(WifiRadioStateListener)}.
 *
 * <p>Called on the main thread after every change; implementations must be fast and must not call
 * {@code RadioDriver} methods synchronously.
 */
@FunctionalInterface
public interface WifiRadioStateListener {
    /**
     * @param state the new snapshot (never null)
     */
    void onWifiRadioStateChanged(WifiRadioState state);
}
