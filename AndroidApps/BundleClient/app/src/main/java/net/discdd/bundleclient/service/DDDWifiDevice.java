package net.discdd.bundleclient.service;

import net.discdd.client.bundletransmission.TransportDevice;

import java.net.InetAddress;
import java.util.List;

public interface DDDWifiDevice extends TransportDevice, Comparable<DDDWifiDevice> {
    String getWifiAddress();

    String getDescription();

    List<InetAddress> getInetAddresses();

    @Override
    default int compareTo(DDDWifiDevice o) {
        int cmp = this.getDescription().compareTo(o.getDescription());
        return cmp != 0 ? cmp : this.getWifiAddress().compareTo(o.getWifiAddress());
    }

}
