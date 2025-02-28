package net.discdd.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;

public class DiscoveredService {
    private final String deviceName;
    private final String registrationType;
    private final WifiP2pDevice device;
    private final String ipAddress;
    private final int port;

    public DiscoveredService(WifiP2pDevice device, String ipAddress, int port) {
        this.deviceName = device.deviceName;
        this.registrationType = "ddd_transport_service";
        this.device = device;
        this.ipAddress = ipAddress;
        this.port = port;
    }

    public String getDeviceName() {
        return deviceName;
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
