package net.discdd.bundledropbox.service;

/**
 * Decides what the Status tab's "Last error" card shows, and when it goes away (audit m12,
 * {@code lasterror-never-cleared}: every writer used to set a value and nothing ever cleared it, so a
 * first-launch error stayed red after the problem was fixed).
 *
 * <p>The card shows one error at a time: the latest one reported. Each error remembers its {@link Source}, and
 * the event that proves that source healthy again clears it:
 * <ul>
 *   <li>{@link Source#STARTUP}: cleared when an initialization attempt succeeds.</li>
 *   <li>{@link Source#FOREGROUND}: cleared when {@code startForeground} next succeeds.</li>
 *   <li>{@link Source#RADIO}: cleared when the cycle reaches HOSTING or VISITING (a role works again).</li>
 *   <li>{@link Source#VISIT}: cleared by the next successful carrier exchange. Reaching HOSTING does not clear
 *       it, so the operator still sees that the last visit failed.</li>
 * </ul>
 * A newer error replaces an older one of any source, so clearing never hides a different, still-current error.
 *
 * <p>Thread-safe. The service publishes {@link #current()} inside its status update function.
 */
public final class StatusErrors {
    /** Where an error came from; decides which success clears it. */
    public enum Source { STARTUP, FOREGROUND, RADIO, VISIT }

    private Source source; // guarded by this
    private String message; // guarded by this

    /** Records {@code message} as the error to show. */
    public synchronized void report(Source source, String message) {
        if (source == null || message == null) throw new NullPointerException("source and message required");
        this.source = source;
        this.message = message;
    }

    /**
     * Clears the shown error if it came from {@code healed}.
     *
     * @return true if something was cleared (the caller then republishes status)
     */
    public synchronized boolean clear(Source healed) {
        if (message == null || source != healed) return false;
        source = null;
        message = null;
        return true;
    }

    /** @return the error to show, or {@code null} for none */
    public synchronized String current() {
        return message;
    }
}
