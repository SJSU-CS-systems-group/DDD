package net.discdd.bundledropbox.wifi;

/**
 * Human-readable names for {@code WifiP2pManager.ActionListener#onFailure(int)} reason codes, used in
 * failure reasons that reach {@code DropboxStatus.lastError} and the UI log.
 *
 * <p>The values mirror {@code WifiP2pManager.ERROR/P2P_UNSUPPORTED/BUSY/NO_SERVICE_REQUESTS}; they are
 * duplicated here so this class (and code that formats reasons) stays pure Java and JVM-testable.
 */
public final class P2pReasons {
    /** {@code WifiP2pManager.ERROR}: internal error (also returned by removeGroup when there is no group). */
    public static final int ERROR = 0;
    /** {@code WifiP2pManager.P2P_UNSUPPORTED}: the device has no Wi-Fi Direct. */
    public static final int P2P_UNSUPPORTED = 1;
    /** {@code WifiP2pManager.BUSY}: framework busy with a previous request; retried with backoff. */
    public static final int BUSY = 2;
    /** {@code WifiP2pManager.NO_SERVICE_REQUESTS}: discoverServices without addServiceRequest. */
    public static final int NO_SERVICE_REQUESTS = 3;

    private P2pReasons() {
    }

    /**
     * @param reason failure code from an ActionListener
     * @return e.g. {@code "BUSY (2)"}
     */
    public static String describe(int reason) {
        String name = switch (reason) {
            case ERROR -> "ERROR";
            case P2P_UNSUPPORTED -> "P2P_UNSUPPORTED";
            case BUSY -> "BUSY";
            case NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS";
            default -> "UNKNOWN";
        };
        return name + " (" + reason + ")";
    }
}
