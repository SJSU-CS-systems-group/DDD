package net.discdd.bundledropbox.status;

import java.util.Objects;

/**
 * Immutable snapshot of the dropbox's control channel for the Status and Storage tabs: the dropbox's own client
 * id at the server, the server keys it trusts, the newest inventory report and retention plan, and how much the
 * server has had deleted (06 Storage Management Design "UI"). Published by {@code control/DropboxControlManager}
 * and carried in {@link DropboxStatus#control()}.
 *
 * <p>Times are the dropbox's wall clock (epoch millis), 0 when the event has not happened yet.
 */
public final class ControlStatus {
    /** Before the service has opened the control endpoint. */
    public static final ControlStatus NONE = new ControlStatus(false, null, "", 0, 0, 0, 0, 0, 0, 0, 0, null);

    private final boolean available;
    private final String endpointClientId;
    private final String serverKeys;
    private final long lastReportSeq;
    private final long lastReportAtMillis;
    private final long lastPlanSeq;
    private final long lastPlanAtMillis;
    private final long deletedUpstreamCount;
    private final long deletedUpstreamBytes;
    private final long deletedDownstreamCount;
    private final long deletedDownstreamBytes;
    private final String problem;

    /**
     * @param available              true if the control endpoint is open
     * @param endpointClientId       the dropbox's DDD client id, or {@code null}
     * @param serverKeys             which server keys are trusted ("built-in", "imported (host:port)")
     * @param lastReportSeq          newest inventory queued (0 = none)
     * @param lastReportAtMillis     when it was queued
     * @param lastPlanSeq            newest plan applied (0 = none)
     * @param lastPlanAtMillis       when it was applied
     * @param deletedUpstreamCount   UPSTREAM bundles deleted on server orders
     * @param deletedUpstreamBytes   their bytes
     * @param deletedDownstreamCount DOWNSTREAM bundles deleted on server orders
     * @param deletedDownstreamBytes their bytes
     * @param problem                why the channel is unavailable or degraded, or {@code null}
     */
    public ControlStatus(boolean available, String endpointClientId, String serverKeys, long lastReportSeq,
                         long lastReportAtMillis, long lastPlanSeq, long lastPlanAtMillis, long deletedUpstreamCount,
                         long deletedUpstreamBytes, long deletedDownstreamCount, long deletedDownstreamBytes,
                         String problem) {
        this.available = available;
        this.endpointClientId = endpointClientId;
        this.serverKeys = serverKeys == null ? "" : serverKeys;
        this.lastReportSeq = lastReportSeq;
        this.lastReportAtMillis = lastReportAtMillis;
        this.lastPlanSeq = lastPlanSeq;
        this.lastPlanAtMillis = lastPlanAtMillis;
        this.deletedUpstreamCount = deletedUpstreamCount;
        this.deletedUpstreamBytes = deletedUpstreamBytes;
        this.deletedDownstreamCount = deletedDownstreamCount;
        this.deletedDownstreamBytes = deletedDownstreamBytes;
        this.problem = problem;
    }

    /** @return the status of a control channel that could not be opened */
    public static ControlStatus unavailable(String serverKeys, String problem) {
        return new ControlStatus(false, null, serverKeys, 0, 0, 0, 0, 0, 0, 0, 0, problem);
    }

    /** @return true if the control endpoint is open (server-managed cleanup is active) */
    public boolean available() { return available; }

    /** @return the dropbox's own DDD client id, or {@code null} */
    public String endpointClientId() { return endpointClientId; }

    /** @return operator-facing description of the trusted server keys (empty if unknown) */
    public String serverKeys() { return serverKeys; }

    /** @return newest inventory queued, 0 if none yet */
    public long lastReportSeq() { return lastReportSeq; }

    /** @return when it was queued (wall clock), 0 if none yet */
    public long lastReportAtMillis() { return lastReportAtMillis; }

    /** @return newest plan applied, 0 if none yet */
    public long lastPlanSeq() { return lastPlanSeq; }

    /** @return when it was applied (wall clock), 0 if none yet */
    public long lastPlanAtMillis() { return lastPlanAtMillis; }

    /** @return UPSTREAM bundles deleted on server orders */
    public long deletedUpstreamCount() { return deletedUpstreamCount; }

    /** @return their bytes */
    public long deletedUpstreamBytes() { return deletedUpstreamBytes; }

    /** @return DOWNSTREAM bundles deleted on server orders */
    public long deletedDownstreamCount() { return deletedDownstreamCount; }

    /** @return their bytes */
    public long deletedDownstreamBytes() { return deletedDownstreamBytes; }

    /** @return why the channel is unavailable or degraded, or {@code null} */
    public String problem() { return problem; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ControlStatus)) return false;
        ControlStatus c = (ControlStatus) o;
        return available == c.available && lastReportSeq == c.lastReportSeq &&
                lastReportAtMillis == c.lastReportAtMillis && lastPlanSeq == c.lastPlanSeq &&
                lastPlanAtMillis == c.lastPlanAtMillis && deletedUpstreamCount == c.deletedUpstreamCount &&
                deletedUpstreamBytes == c.deletedUpstreamBytes && deletedDownstreamCount == c.deletedDownstreamCount &&
                deletedDownstreamBytes == c.deletedDownstreamBytes &&
                Objects.equals(endpointClientId, c.endpointClientId) && serverKeys.equals(c.serverKeys) &&
                Objects.equals(problem, c.problem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(available, endpointClientId, serverKeys, lastReportSeq, lastReportAtMillis, lastPlanSeq,
                            lastPlanAtMillis, deletedUpstreamCount, deletedUpstreamBytes, deletedDownstreamCount,
                            deletedDownstreamBytes, problem);
    }

    @Override
    public String toString() {
        return "ControlStatus{" + (available ? "client=" + endpointClientId : "unavailable") + ", keys=" +
                serverKeys + ", report#" + lastReportSeq + ", plan#" + lastPlanSeq + ", deleted up=" +
                deletedUpstreamCount + " down=" + deletedDownstreamCount +
                (problem == null ? "" : ", problem=" + problem) + "}";
    }
}
