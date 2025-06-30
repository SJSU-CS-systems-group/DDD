package net.discdd.bundleclient.service;

import java.net.InetAddress;
import java.util.List;

public interface DDDWifiDevice {
    String getWifiAddress();

    byte[] getAssociatedData();

    String getDescription();

    /**
     * Returns the list of InetAddresses associated with this device.
     *
     * @return null if device is not connected
     */
    List<InetAddress> getInetAddresses();
}
