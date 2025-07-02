package net.discdd.bundleclient.service;

import net.discdd.client.bundletransmission.TransportDevice;

public interface DDDWifiDevice extends TransportDevice, Comparable<DDDWifiDevice> {

    /**
     * Returns the wifi address of the device.
     * This is usually the MAC address or similar unique identifier.
     *
     * @return The wifi address as a String.
     */
    String getWifiAddress();

    String getDescription();

    @Override
    default int compareTo(DDDWifiDevice o) {
        int cmp = this.getDescription().compareTo(o.getDescription());
        return cmp != 0 ? cmp : this.getWifiAddress().compareTo(o.getWifiAddress());
    }

}
