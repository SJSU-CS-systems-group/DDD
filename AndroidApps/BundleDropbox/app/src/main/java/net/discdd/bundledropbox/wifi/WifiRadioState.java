package net.discdd.bundledropbox.wifi;

import java.util.List;

/**
 * Immutable snapshot of the dropbox's Wi-Fi Direct radio for the UI (Local Wifi tab).
 *
 * <p>Published by {@link WifiDirectRadioDriver} to {@link WifiRadioStateListener}s on the main thread
 * after every change. It is deliberately Kotlin-free; {@link DropboxWifiStateBridge} mirrors it into the
 * UI's {@code DropboxWifiState} StateFlow. It carries only what the UI shows (audit CL-2: phase, permission,
 * advertised-id and last-error fields nobody read were removed).
 *
 * <p>Host-side fields ({@link #group()}) describe the dropbox's own group while hosting; visit-side
 * fields ({@link #currentCarrier()}, {@link #discoveredCarriers()}) describe carriers during VISIT.
 */
public final class WifiRadioState {
    /** Snapshot with nothing known yet. */
    public static final WifiRadioState INITIAL = builder().build();

    private final boolean p2pEnabled;
    private final String deviceName;
    private final GroupInfo group;
    private final DiscoveredCarrier currentCarrier;
    private final List<DiscoveredCarrier> discoveredCarriers;

    private WifiRadioState(Builder b) {
        this.p2pEnabled = b.p2pEnabled;
        this.deviceName = b.deviceName;
        this.group = b.group;
        this.currentCarrier = b.currentCarrier;
        this.discoveredCarriers = List.copyOf(b.discoveredCarriers);
    }

    /** @return a builder with default (empty) values */
    public static Builder builder() { return new Builder(); }

    /** @return true if the last {@code WIFI_P2P_STATE_CHANGED_ACTION} said Wi-Fi Direct is enabled */
    public boolean p2pEnabled() { return p2pEnabled; }

    /** @return this phone's P2P device name (what clients show), or null if unknown */
    public String deviceName() { return deviceName; }

    /** @return the P2P group this device is in (own group while hosting, carrier's while visiting), or null */
    public GroupInfo group() { return group; }

    /** @return carrier being joined or exchanged with, or null */
    public DiscoveredCarrier currentCarrier() { return currentCarrier; }

    /** @return carriers seen in the current visit period, in discovery order */
    public List<DiscoveredCarrier> discoveredCarriers() { return discoveredCarriers; }

    @Override
    public String toString() {
        return "WifiRadioState{" + (p2pEnabled ? "p2p enabled" : "p2p disabled") + ", group=" + group +
                ", carrier=" + currentCarrier + ", discovered=" + discoveredCarriers.size() + "}";
    }

    /** Mutable builder; not thread-safe. */
    public static final class Builder {
        private boolean p2pEnabled;
        private String deviceName;
        private GroupInfo group;
        private DiscoveredCarrier currentCarrier;
        private List<DiscoveredCarrier> discoveredCarriers = List.of();

        private Builder() {
        }

        public Builder p2pEnabled(boolean v) { p2pEnabled = v; return this; }

        public Builder deviceName(String v) { deviceName = v; return this; }

        public Builder group(GroupInfo v) { group = v; return this; }

        public Builder currentCarrier(DiscoveredCarrier v) { currentCarrier = v; return this; }

        public Builder discoveredCarriers(List<DiscoveredCarrier> v) {
            discoveredCarriers = v == null ? List.of() : v;
            return this;
        }

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
