package net.discdd.bundleclient.service.wifiDirect;

import android.net.wifi.p2p.WifiP2pDevice;
import net.discdd.bundleclient.service.DDDWifiDevice;

class DDDWifiDirectDevice implements DDDWifiDevice {
    final public WifiP2pDevice wifiP2pDevice;

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

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DDDWifiDirectDevice owdd)) return false;
        return this.compareTo(owdd) == 0;
    }

    @Override
    public int hashCode() {
        return this.wifiP2pDevice.deviceAddress.hashCode();
    }
}
