package net.discdd.bundledropbox.wifi;

/**
 * Immutable, UI-facing view of one carrier (BundleTransport) seen by DNS-SD while the dropbox is in VISIT
 * mode. Produced by {@link CarrierSelector#snapshot()} and carried in {@link WifiRadioState}.
 *
 * <p>Times are monotonic milliseconds from the driver's clock ({@code SystemClock.elapsedRealtime()} on
 * Android), except {@link #recencyBlobTimestamp()}, which is the server's wall clock.
 */
public final class DiscoveredCarrier {
    /** Value of {@link #lastVisitedAtMillis()} for a carrier never visited by this process. */
    public static final long NEVER = -1L;

    private final String carrierId;
    private final String deviceAddress;
    private final String deviceName;
    private final long recencyBlobTimestamp;
    private final long firstSeenAtMillis;
    private final long lastSeenAtMillis;
    private final long lastVisitedAtMillis;
    private final boolean lastVisitOk;
    private final boolean attemptedThisPeriod;

    /**
     * @param carrierId            the carrier's TXT {@code transportId}
     * @param deviceAddress        P2P device MAC used for {@code WifiP2pConfig.deviceAddress}
     * @param deviceName           human-readable P2P device name (may be empty)
     * @param recencyBlobTimestamp server timestamp of the advertised SUCCESS blob, 0 if none
     * @param firstSeenAtMillis    first TXT sighting in the current visit period
     * @param lastSeenAtMillis     latest TXT sighting
     * @param lastVisitedAtMillis  end of the last exchange with this carrier, or {@link #NEVER}
     * @param lastVisitOk          whether that exchange succeeded
     * @param attemptedThisPeriod  true once the dropbox tried to join it during the current visit period
     */
    public DiscoveredCarrier(String carrierId,
                             String deviceAddress,
                             String deviceName,
                             long recencyBlobTimestamp,
                             long firstSeenAtMillis,
                             long lastSeenAtMillis,
                             long lastVisitedAtMillis,
                             boolean lastVisitOk,
                             boolean attemptedThisPeriod) {
        this.carrierId = carrierId;
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.recencyBlobTimestamp = recencyBlobTimestamp;
        this.firstSeenAtMillis = firstSeenAtMillis;
        this.lastSeenAtMillis = lastSeenAtMillis;
        this.lastVisitedAtMillis = lastVisitedAtMillis;
        this.lastVisitOk = lastVisitOk;
        this.attemptedThisPeriod = attemptedThisPeriod;
    }

    /** @return the carrier's TXT {@code transportId}; the {@code ForwardLedger} key */
    public String carrierId() { return carrierId; }

    /** @return P2P device MAC address */
    public String deviceAddress() { return deviceAddress; }

    /** @return P2P device name, never null */
    public String deviceName() { return deviceName; }

    /** @return server timestamp of the advertised SUCCESS recency blob, 0 if none */
    public long recencyBlobTimestamp() { return recencyBlobTimestamp; }

    /** @return monotonic time of the first sighting in this visit period */
    public long firstSeenAtMillis() { return firstSeenAtMillis; }

    /** @return monotonic time of the latest sighting */
    public long lastSeenAtMillis() { return lastSeenAtMillis; }

    /** @return monotonic end time of the last exchange, or {@link #NEVER} */
    public long lastVisitedAtMillis() { return lastVisitedAtMillis; }

    /** @return true if the last exchange with this carrier succeeded */
    public boolean lastVisitOk() { return lastVisitOk; }

    /** @return true if already tried during the current visit period (it will not be tried again) */
    public boolean attemptedThisPeriod() { return attemptedThisPeriod; }

    /** @return short label for logs and UI: device name if known, else carrier id */
    public String label() {
        return deviceName.isBlank() ? carrierId : deviceName + " (" + carrierId + ")";
    }

    @Override
    public String toString() {
        return "DiscoveredCarrier{" + carrierId + ", " + deviceName + " " + deviceAddress + ", blobTs=" +
                recencyBlobTimestamp + ", lastVisited=" + lastVisitedAtMillis + (lastVisitOk ? " ok" : "") +
                (attemptedThisPeriod ? ", attempted" : "") + "}";
    }
}
