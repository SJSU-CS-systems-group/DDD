package net.discdd.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;

import androidx.annotation.NonNull;

import java.util.Locale;

public class DiscoveredService {
    private final String deviceName;
    private final String registrationType;
    private final WifiP2pDevice device;
    private final String deviceAddress;
    private final int port;

    public DiscoveredService(WifiP2pDevice device, String deviceAddress, int port) {
        this.deviceName = device.deviceName;
        this.registrationType = "ddd_transport_service";
        this.device = device;
        this.deviceAddress = deviceAddress;
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

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public int getPort() {
        return port;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "Device Name: %s\nRegistration Type: %s\nMAC Address: %s\nPort: %d",
                             deviceName, registrationType, deviceAddress, port);
    }
}
