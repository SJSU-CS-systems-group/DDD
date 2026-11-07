package net.discdd.bugreport;

/**
 * Constants of the bug report channel. BundleClient queues each user-submitted bug report as one upstream ADU
 * under {@link #APP_ID}; it rides the client's next bundle through any carrier (transport or dropbox) to the server
 * the client is bound to, which consumes it in-process and stores it under its shared bundle store. Nothing is sent
 * back downstream.
 */
public final class BugReport {
    /**
     * ADU app ID of bug reports. Like the dropbox control IDs, it is not an Android package name, so no installed
     * app can claim it through BundleClient's {@code MessageProvider}.
     */
    public static final String APP_ID = "net.discdd.bugreport";

    /**
     * Largest report the client queues, in bytes (256 KiB). The user's text and the in-memory log buffer (about 100
     * lines) are far below this; the bound keeps a runaway report from crowding the bundle.
     */
    public static final int MAX_REPORT_BYTES = 256 * 1024;

    private BugReport() {}
}
