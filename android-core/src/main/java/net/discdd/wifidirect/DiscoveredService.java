package net.discdd.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;

public class DiscoveredService {
    private final String instanceName;
    private final String registrationType;
    private final WifiP2pDevice device;
    private final String ipAddress;
    private final int port;

    public DiscoveredService(WifiP2pDevice device, String ipAddress, int port) {
        this.device = device;
        this.ipAddress = ipAddress;
        this.port = port;
        this.instanceName = device.deviceName;
        this.registrationType = "ddd_wifi_direct_service";
    }

    public String getInstanceName() {
        return instanceName;
    }

    public String getRegistrationType() {
        return registrationType;
    }

    public WifiP2pDevice getDevice() {
        return device;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }
}
