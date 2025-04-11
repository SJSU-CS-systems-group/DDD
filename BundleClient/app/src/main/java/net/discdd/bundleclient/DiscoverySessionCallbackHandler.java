package net.discdd.bundleclient;

import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.ServiceDiscoveryInfo;
import android.net.wifi.aware.SubscribeDiscoverySession;

import androidx.annotation.NonNull;

import net.discdd.wifiaware.WifiAwareHelper;

import java.util.function.Consumer;

public class DiscoverySessionCallbackHandler extends DiscoverySessionCallback {
    private final BundleClientWifiAwareService service;
    private final Consumer<PeerHandle> serviceLostReceiver;
    private final Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver;
    private final Consumer<WifiAwareHelper.PeerMessage> messageReceiver;
    private SubscribeDiscoverySession session;

    public DiscoverySessionCallbackHandler(BundleClientWifiAwareService service,
                                           Consumer<PeerHandle> serviceLostReceiver,
                                           Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver,
                                           Consumer<WifiAwareHelper.PeerMessage> messageReceiver) {
        this.service = service;
        this.serviceLostReceiver = serviceLostReceiver;
        this.serviceDiscoveryReceiver = serviceDiscoveryReceiver;
        this.messageReceiver = messageReceiver;
    }

    @Override
    public void onServiceLost(@NonNull PeerHandle peerHandle, int reason) {
        serviceLostReceiver.accept(peerHandle);
    }

    @Override
    public void onServiceDiscovered(@NonNull ServiceDiscoveryInfo serviceDiscoveryInfo) {
        serviceDiscoveryReceiver.accept(serviceDiscoveryInfo);
    }

    @Override
    public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
        messageReceiver.accept(new WifiAwareHelper.PeerMessage(peerHandle, message));
    }

    @Override
    public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
        service.setSession(session);
    }

    public SubscribeDiscoverySession getSession() {
        return session;
    }
}