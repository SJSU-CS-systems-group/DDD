package net.discdd.wifiaware;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.ServiceDiscoveryInfo;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import net.discdd.bundleclient.BundleClientWifiAwareSubscriber;
import net.discdd.bundletransport.TransportWifiAwarePublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class WifiAwareHelper {
    public final Context context;
    private final HashMap<PeerHandle, ConnectionInfo> connections = new HashMap<>();
    private WifiAwareSession wifiAwareSession;

    public WifiAwareHelper(Context context) {
        this.context = context;
    }

    public CompletableFuture<Boolean> initialize() {
        WifiAwareManager wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        var future = new CompletableFuture<Boolean>();
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable()) {
            future.completeExceptionally(new WiFiAwareException("Wi-Fi Aware is not available"));
            return future;
        }

        wifiAwareManager.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                wifiAwareSession = session;
                future.complete(true);
            }

            @Override
            public void onAttachFailed() {
                future.complete(false);
            }

            @Override
            public void onAwareSessionTerminated() {
                future.complete(false);
            }
        }, null);
        return future;
    }

    @RequiresPermission(allOf = {
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            ACCESS_FINE_LOCATION,
            NEARBY_WIFI_DEVICES}, conditional = true)
    public TransportWifiAwarePublisher registerService(final String serviceName,
                                                    final byte[] description,
                                                    final int port,
                                                    Consumer<PeerMessage> messageReceiver) throws WiFiAwareException {
        if (wifiAwareSession == null) {
            throw new WiFiAwareException("Wi-Fi Aware session is not initialized");
        }
        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(description)
                .build();
        if (wifiAwareSession == null) {
            throw new WiFiAwareException("Wi-Fi Aware session is not initialized");
        }
        var publisherHelper = new TransportWifiAwarePublisher(this,
                                                              serviceName,
                                                              description,
                                                              port,
                                                              messageReceiver);
        wifiAwareSession.publish(publishConfig, publisherHelper, null);
        return publisherHelper;
    }

    @RequiresPermission(allOf = {
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            ACCESS_FINE_LOCATION,
            NEARBY_WIFI_DEVICES}, conditional = true)
    public BundleClientWifiAwareSubscriber startDiscovery(final String serviceName,
                                                          final byte[] serviceSpecificInfo,
                                                          List<byte[]> matchFilter,
                                                          Consumer<PeerMessage> messageReceiver,
                                                          Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver,
                                                          Consumer<PeerHandle> serviceLostReceiver) throws WiFiAwareException {
        if (wifiAwareSession == null) {
            throw new WiFiAwareException("Wi-Fi Aware session is not initialized");
        }
        var configBuilder = new SubscribeConfig.Builder();
        if (serviceName != null) configBuilder.setServiceName(serviceName);
        if (serviceSpecificInfo != null) configBuilder.setServiceSpecificInfo(serviceSpecificInfo);
        if (matchFilter != null) configBuilder.setMatchFilter(matchFilter);
        BundleClientWifiAwareSubscriber subscriberHelper = new BundleClientWifiAwareSubscriber(this,
                                                                                   serviceName,
                                                                                   serviceSpecificInfo,
                                                                                   matchFilter,
                                                                                   messageReceiver,
                                                                                   serviceDiscoveryReceiver,
                                                                                   serviceLostReceiver);
        wifiAwareSession.subscribe(configBuilder.build(), subscriberHelper, null);
        return subscriberHelper;

    }

    public CompletableFuture<WifiAwareNetworkInfo> getConnectivityManager(DiscoverySession discoverySession, PeerHandle peerHandle, int port) {
        var future = new CompletableFuture<WifiAwareNetworkInfo>();
        var conInfo = connections.get(peerHandle);
        if (conInfo == null) {
            var newConInfo = new ConnectionInfo();
            // we need this to disconnect the network when we are done
            var networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities networkCapabilities) {
                    newConInfo.setNetwork((WifiAwareNetworkInfo) networkCapabilities.getTransportInfo());
                }
            };
            newConInfo.peerHandle = peerHandle;
            newConInfo.callback = networkCallback;
            conInfo = newConInfo;
            connections.put(peerHandle, conInfo);
        }
        conInfo.fillInNetwork(future);

        WifiAwareNetworkSpecifier networkSpecifier = new WifiAwareNetworkSpecifier.Builder(
                discoverySession,
                peerHandle).setPskPassphrase("DiscDataDist")
                // 0 indicates port not used in the Builder
                .setPort(Math.max(port, 0)).build();

        NetworkRequest networkRequest = new NetworkRequest.Builder().addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build();

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        connectivityManager.requestNetwork(networkRequest, conInfo.callback);
        return future;
    }

    public static class WiFiAwareException extends Exception {
        public WiFiAwareException(String message) {
            super(message);
        }
    }

    static private class ConnectionInfo {
        final ArrayList<CompletableFuture<WifiAwareNetworkInfo>> toBeCompleted = new ArrayList<>();
        PeerHandle peerHandle;
        WifiAwareNetworkInfo networkInfo;
        ConnectivityManager.NetworkCallback callback;

        void fillInNetwork(CompletableFuture<WifiAwareNetworkInfo> future) {
            WifiAwareNetworkInfo preSetNetworkInfo = null;
            synchronized (this) {
                if (networkInfo == null) {
                    toBeCompleted.add(future);
                } else {
                    preSetNetworkInfo = networkInfo;
                }
            }
            // we need to do the complete() outside of the synchronized block to avoid deadlock
            // that is why we have to do the preSetNetwork dance
            if (preSetNetworkInfo != null) {
                future.complete(preSetNetworkInfo);
            }
        }

        void setNetwork(WifiAwareNetworkInfo networkInfo) {
            this.networkInfo = networkInfo;
            ArrayList<CompletableFuture<WifiAwareNetworkInfo>> completeNow;
            synchronized (this) {
                completeNow = new ArrayList<>(toBeCompleted);
                toBeCompleted.clear();
            }
            // do the complete() outside of the synchronized block to avoid deadlock
            completeNow.forEach(f -> f.complete(networkInfo));
        }
    }

    public record PeerMessage(PeerHandle peerHandle, byte[] message) {
    }
}