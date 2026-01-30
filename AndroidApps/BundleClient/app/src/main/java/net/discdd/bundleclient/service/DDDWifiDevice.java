package net.discdd.bundleclient.service;

import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.GetRecencyBlobResponse;

import android.net.wifi.p2p.WifiP2pDevice;

public interface DDDWifiDevice extends TransportDevice, Comparable<DDDWifiDevice> {

    /**
     * Returns the wifi address of the device.
     * This is usually the MAC address or similar unique identifier.
     *
     * @return The wifi address as a String.
     */
    String getWifiAddress();

    String getDescription();

    GetRecencyBlobResponse getRecencyBlob();

    void setRecencyBlob(GetRecencyBlobResponse recencyBlob);

    void setWifiP2pDevice(WifiP2pDevice wifiP2pDevice);

    @Override
    default int compareTo(DDDWifiDevice o) {
        return this.getId().compareTo(o.getId());
    }

}
