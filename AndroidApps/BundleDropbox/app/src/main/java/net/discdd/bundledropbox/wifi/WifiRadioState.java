package net.discdd.bundledropbox.wifi;

import java.util.List;

/**
 * Immutable snapshot of the dropbox's Wi-Fi Direct radio for the UI (Local Wifi tab) and logs.
 *
 * <p>Published by {@link WifiDirectRadioDriver} to {@link WifiRadioStateListener}s on the main thread
 * after every change, and readable at any time with {@link WifiDirectRadioDriver#getState()}. It is
 * deliberately Kotlin-free; {@link DropboxWifiStateBridge} mirrors it into the UI's
 * {@code DropboxWifiState} StateFlow.
 *
 * <p>Host-side fields ({@link #group()}) describe the dropbox's own group while hosting; visit-side
 * fields ({@link #currentCarrier()}, {@link #discoveredCarriers()}) describe carriers during VISIT.
 */
public final class WifiRadioState {
    /** What the single P2P interface is doing right now. */
    public enum Phase {
        /** Driver not initialized, or shut down. */
        OFF,
        /** Initialized, neither hosting nor visiting. */
        IDLE,
        /** startHosting in progress (group create, endpoint start, service add). */
        HOST_STARTING,
        /** Group formed, endpoint running, DNS-SD service advertised. */
        HOSTING,
        /** stopHosting in progress. */
        HOST_STOPPING,
        /** startVisit in progress (leave groups, register service request). */
        VISIT_STARTING,
        /** DNS-SD discovery running, no carrier joined. */
        DISCOVERING,
        /** Joining a carrier's group. */
        CONNECTING,
        /** Joined; the {@link VisitRunner} is exchanging with the carrier. */
        EXCHANGING,
        /** Leaving a carrier's group after an exchange or failed attempt. */
        DISCONNECTING,
        /** stopVisit in progress. */
        VISIT_STOPPING
    }

    /** Snapshot with nothing known yet. */
    public static final WifiRadioState INITIAL = builder().build();

    private final Phase phase;
    private final boolean p2pEnabled;
    private final boolean permissionsGranted;
    private final String deviceName;
    private final String advertisedId;
    private final GroupInfo group;
    private final DiscoveredCarrier currentCarrier;
    private final List<DiscoveredCarrier> discoveredCarriers;
    private final String lastError;

    private WifiRadioState(Builder b) {
        this.phase = b.phase;
        this.p2pEnabled = b.p2pEnabled;
        this.permissionsGranted = b.permissionsGranted;
        this.deviceName = b.deviceName;
        this.advertisedId = b.advertisedId;
        this.group = b.group;
        this.currentCarrier = b.currentCarrier;
        this.discoveredCarriers = List.copyOf(b.discoveredCarriers);
        this.lastError = b.lastError;
    }

    /** @return a builder with default (empty) values */
    public static Builder builder() { return new Builder(); }

    /** @return a builder pre-filled with this snapshot's values */
    public Builder toBuilder() {
        return new Builder().phase(phase)
                .p2pEnabled(p2pEnabled)
                .permissionsGranted(permissionsGranted)
                .deviceName(deviceName)
                .advertisedId(advertisedId)
                .group(group)
                .currentCarrier(currentCarrier)
                .discoveredCarriers(discoveredCarriers)
                .lastError(lastError);
    }

    /** @return what the radio is doing */
    public Phase phase() { return phase; }

    /** @return true if the last {@code WIFI_P2P_STATE_CHANGED_ACTION} said Wi-Fi Direct is enabled */
    public boolean p2pEnabled() { return p2pEnabled; }

    /** @return true if NEARBY_WIFI_DEVICES (and ACCESS_FINE_LOCATION, needed for visits) are granted */
    public boolean permissionsGranted() { return permissionsGranted; }

    /** @return this phone's P2P device name (what clients show), or null if unknown */
    public String deviceName() { return deviceName; }

    /** @return TXT {@code transportId} currently (or last) advertised while hosting, or null */
    public String advertisedId() { return advertisedId; }

    /** @return the P2P group this device is in (own group while hosting, carrier's while visiting), or null */
    public GroupInfo group() { return group; }

    /** @return carrier being joined or exchanged with, or null */
    public DiscoveredCarrier currentCarrier() { return currentCarrier; }

    /** @return carriers seen in the current visit period, in discovery order */
    public List<DiscoveredCarrier> discoveredCarriers() { return discoveredCarriers; }

    /** @return most recent radio error, or null */
    public String lastError() { return lastError; }

    @Override
    public String toString() {
        return "WifiRadioState{" + phase + (p2pEnabled ? "" : ", p2p disabled") +
                (permissionsGranted ? "" : ", no permission") + ", group=" + group + ", carrier=" + currentCarrier +
                ", discovered=" + discoveredCarriers.size() + (lastError == null ? "" : ", error=" + lastError) + "}";
    }

    /** Mutable builder; not thread-safe. */
    public static final class Builder {
        private Phase phase = Phase.OFF;
        private boolean p2pEnabled;
        private boolean permissionsGranted;
        private String deviceName;
        private String advertisedId;
        private GroupInfo group;
        private DiscoveredCarrier currentCarrier;
        private List<DiscoveredCarrier> discoveredCarriers = List.of();
        private String lastError;

        private Builder() {
        }

        public Builder phase(Phase v) { phase = v; return this; }

        public Builder p2pEnabled(boolean v) { p2pEnabled = v; return this; }

        public Builder permissionsGranted(boolean v) { permissionsGranted = v; return this; }

        public Builder deviceName(String v) { deviceName = v; return this; }

        public Builder advertisedId(String v) { advertisedId = v; return this; }

        public Builder group(GroupInfo v) { group = v; return this; }

        public Builder currentCarrier(DiscoveredCarrier v) { currentCarrier = v; return this; }

        public Builder discoveredCarriers(List<DiscoveredCarrier> v) {
            discoveredCarriers = v == null ? List.of() : v;
            return this;
        }

        public Builder lastError(String v) { lastError = v; return this; }

        public WifiRadioState build() { return new WifiRadioState(this); }
    }

    /**
     * Immutable view of a {@code WifiP2pGroup}, without Android types.
     */
    public static final class GroupInfo {
        private final String networkName;
        private final String passphrase;
        private final String groupOwnerAddress;
        private final boolean groupOwner;
        private final List<String> clientNames;

        /**
         * @param networkName       SSID, e.g. {@code DIRECT-ddd_<baseId>}
         * @param passphrase        WPA2 passphrase (only known to the group owner), may be null
         * @param groupOwnerAddress GO IPv4 address as text, may be null if not known yet
         * @param groupOwner        true if this device is the group owner (HOST), false when joined to a carrier
         * @param clientNames       P2P client device names (empty when we are a client)
         */
        public GroupInfo(String networkName,
                         String passphrase,
                         String groupOwnerAddress,
                         boolean groupOwner,
                         List<String> clientNames) {
            this.networkName = networkName;
            this.passphrase = passphrase;
            this.groupOwnerAddress = groupOwnerAddress;
            this.groupOwner = groupOwner;
            this.clientNames = clientNames == null ? List.of() : List.copyOf(clientNames);
        }

        /** @return SSID */
        public String networkName() { return networkName; }

        /** @return passphrase, or null */
        public String passphrase() { return passphrase; }

        /** @return GO IPv4 address text (clients use {@code <it>:7777}), or null */
        public String groupOwnerAddress() { return groupOwnerAddress; }

        /** @return true if this device owns the group */
        public boolean isGroupOwner() { return groupOwner; }

        /** @return connected P2P client names */
        public List<String> clientNames() { return clientNames; }

        /** @return number of connected P2P clients */
        public int clientCount() { return clientNames.size(); }

        @Override
        public String toString() {
            return "GroupInfo{" + networkName + (groupOwner ? " GO " : " client ") + groupOwnerAddress + ", clients=" +
                    clientNames.size() + "}";
        }
    }
}
