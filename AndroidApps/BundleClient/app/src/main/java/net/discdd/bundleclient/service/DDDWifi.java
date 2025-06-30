package net.discdd.bundleclient.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DDDWifi {
    CompletableFuture<Void> startDiscovery();

    boolean isDiscoveryActive();

    String getStateDescription();

    boolean isDddWifiEnabled();

    CompletableFuture<DDDWifiConnection> connectTo(DDDWifiDevice dev);

    List<DDDWifiDevice> listDevices();

    CompletableFuture<Void> disconnectFrom(DDDWifiDevice dev);

    void shutdown();

}
