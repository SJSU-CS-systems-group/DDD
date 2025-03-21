package net.discdd.bundleclient;

import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.ServiceDiscoveryInfo;
import android.net.wifi.aware.SubscribeDiscoverySession;

import androidx.annotation.NonNull;

import net.discdd.wifiaware.WifiAwareHelper;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class BundleClientWifiAwareSubscriber extends DiscoverySessionCallback {
    public final String serviceName;
    public final byte[] serviceSpecificInfo;
    public final List<byte[]> matchFilter;
    private final WifiAwareHelper wiFiAwareHelper;
    private final Consumer<WifiAwareHelper.PeerMessage> messageReceiver;
    private final Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver;
    private final Consumer<PeerHandle> serviceLostReceiver;
    private DiscoverySession session;

    public BundleClientWifiAwareSubscriber(WifiAwareHelper wiFiAwareHelper, String serviceName, byte[] serviceSpecificInfo, List<byte[]> matchFilter, Consumer<WifiAwareHelper.PeerMessage> messageReceiver, Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver, Consumer<PeerHandle> serviceLostReceiver) {
        this.wiFiAwareHelper = wiFiAwareHelper;
        this.serviceName = serviceName;
        this.serviceSpecificInfo = serviceSpecificInfo;
        this.matchFilter = matchFilter;
        this.messageReceiver = messageReceiver;
        this.serviceDiscoveryReceiver = serviceDiscoveryReceiver;
        this.serviceLostReceiver = serviceLostReceiver;
    }

    public CompletableFuture<InetSocketAddress> connectToServer(PeerHandle peerHandle) {
        CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();
        if (session == null) {
            future.completeExceptionally(new WifiAwareHelper.WiFiAwareException(
                    "Subscriber session has not been started yet"));
            return future;
        }
        var connectivityFuture = wiFiAwareHelper.getConnectivityManager(session, peerHandle, -1);
        connectivityFuture.whenComplete((ni, t) -> {
            if (t != null) {
                future.completeExceptionally(t);
            } else if (ni == null) {
                future.completeExceptionally(new WifiAwareHelper.WiFiAwareException(
                        "No peer aware info"));
            } else {
                var peerIpv6 = ni.getPeerIpv6Addr();
                int peerPort = ni.getPort();
                future.complete(new InetSocketAddress(peerIpv6, peerPort));
            }
        });
        return future;
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
        this.session = session;
    }

    public void unsubscribe() {
        session.close();
    }
}

