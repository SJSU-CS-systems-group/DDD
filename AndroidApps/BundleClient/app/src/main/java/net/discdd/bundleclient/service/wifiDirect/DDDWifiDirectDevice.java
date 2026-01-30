package net.discdd.bundleclient.service.wifiDirect;

import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.grpc.GetRecencyBlobResponse;

import android.net.wifi.p2p.WifiP2pDevice;

class DDDWifiDirectDevice implements DDDWifiDevice {
    final public String transportId;
    public WifiP2pDevice wifiP2pDevice;
    public GetRecencyBlobResponse recencyBlob;

    public DDDWifiDirectDevice(WifiP2pDevice wifiP2pDevice, String transportId, GetRecencyBlobResponse recencyBlob) {
        this.wifiP2pDevice = wifiP2pDevice;
        this.transportId = transportId;
        this.recencyBlob = recencyBlob;
    }

    @Override
    public String getWifiAddress() { return wifiP2pDevice.deviceAddress; }

    @Override
    public String getDescription() { return this.wifiP2pDevice.deviceName; }

    @Override
    public String getId() { return transportId; }

    @Override
    public GetRecencyBlobResponse getRecencyBlob() {
        return (recencyBlob == null) ? GetRecencyBlobResponse.getDefaultInstance() : recencyBlob;
    }

    @Override
    public void setRecencyBlob(GetRecencyBlobResponse recencyBlob) { this.recencyBlob = recencyBlob; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DDDWifiDirectDevice owdd)) return false;
        return this.compareTo(owdd) == 0;
    }

    @Override
    public int hashCode() {
        return this.getId().hashCode();
    }

    @Override
    public void setWifiP2pDevice(WifiP2pDevice wifiP2pDevice) { this.wifiP2pDevice = wifiP2pDevice; }

    public boolean sameAddressAs(DDDWifiDevice o) {
        return this.getWifiAddress().compareTo((o).getWifiAddress()) == 0;
    }
}
