package net.discdd.bundleclient.service.wifiDirect;

import android.net.wifi.p2p.WifiP2pDevice;
import net.discdd.bundleclient.service.DDDWifiDevice;

import java.net.InetAddress;
import java.util.List;

class DDDWifiDirectDevice implements DDDWifiDevice {
    final public WifiP2pDevice wifiP2pDevice;
    private List<InetAddress> addresses = List.of();

    public DDDWifiDirectDevice(WifiP2pDevice wifiP2pDevice) {
        this.wifiP2pDevice = wifiP2pDevice;
    }

    @Override
    public String getWifiAddress() {
        return wifiP2pDevice.deviceAddress;
    }

    @Override
    public String getDescription() {
        return this.wifiP2pDevice.deviceName;
    }

    void setAddresses(List<InetAddress> addresses) {
        this.addresses = addresses;
    }

    @Override
    public List<InetAddress> getInetAddresses() {
        return addresses;
    }

    public boolean equals(Object o) {
        if (!(o instanceof DDDWifiDirectDevice owdd)) return false;
        return this.compareTo(owdd) == 0;
    }

    public int hashCode() {
        return this.wifiP2pDevice.deviceName.hashCode() ^ this.wifiP2pDevice.deviceAddress.hashCode();
    }
}
