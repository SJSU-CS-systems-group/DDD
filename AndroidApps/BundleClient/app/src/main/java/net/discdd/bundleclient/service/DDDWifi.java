package net.discdd.bundleclient.service;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DDDWifi {
    CompletableFuture<Boolean> startDiscovery();

    boolean isDiscoveryActive();

    String getStateDescription();

    boolean isDddWifiEnabled();

    CompletableFuture<DDDWifiConnection> connectTo(DDDWifiDevice dev);

    List<DDDWifiDevice> listDevices();

    CompletableFuture<Void> disconnectFrom(DDDWifiConnection con);

    void shutdown();

    LiveData<DDDWifiEventType> getEventLiveData();

    void wifiPermissionGranted();
}
