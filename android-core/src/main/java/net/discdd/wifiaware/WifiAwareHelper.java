package net.discdd.wifiaware;

import static android.net.wifi.aware.WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WifiAwareHelper {
    private static final String TAG = "WifiAwareHelper";

    private final Context context;
    private final List<WifiAwareStateListener> listeners = new ArrayList<>();
    private final IntentFilter intentFilter = new IntentFilter(ACTION_WIFI_AWARE_STATE_CHANGED);
    private final HashMap<PeerHandle, ConnectionInfo> connections = new HashMap<>();

    private WifiAwareSession wifiAwareSession;
    private WifiAwareBroadcastReceiver receiver;
    private WifiAwareManager wifiAwareManager;

    public WifiAwareHelper(Context context) {
        this.context = context;
        this.wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
    }

    public WifiAwareHelper(Context context, WifiAwareStateListener listener) {
        this(context);
        listeners.add(listener);
    }

    public CompletableFuture<Boolean> initialize() {
        var future = new CompletableFuture<Boolean>();

        if (wifiAwareManager == null) {
            future.completeExceptionally(new WiFiAwareException("WiFi Aware service is not available"));
            return future;
        }

        if (!wifiAwareManager.isAvailable()) {
            future.complete(false);
            notifyActionToListeners(WifiAwareEventType.WIFI_AWARE_MANAGER_FAILED, "WiFi Aware is not available");
            return future;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());

        try {
            wifiAwareManager.attach(new AttachCallback() {
                @Override
                public void onAttached(WifiAwareSession session) {
                    mainHandler.post(() -> {
                        synchronized (WifiAwareHelper.this) {
                            wifiAwareSession = session;
                        }
                        future.complete(true);
                        notifyActionToListeners(WifiAwareEventType.WIFI_AWARE_MANAGER_INITIALIZED);
                    });
                }

                @Override
                public void onAttachFailed() {
                    mainHandler.post(() -> {
                        future.complete(false);
                        notifyActionToListeners(WifiAwareEventType.WIFI_AWARE_MANAGER_FAILED, "Failed to attach to WiFi Aware");
                    });
                }

                @Override
                public void onAwareSessionTerminated() {
                    mainHandler.post(() -> {
                        synchronized (WifiAwareHelper.this) {
                            wifiAwareSession = null;
                        }
                        notifyActionToListeners(WifiAwareEventType.WIFI_AWARE_MANAGER_TERMINATED);
                    });
                }
            }, mainHandler);

            if (receiver == null) {
                receiver = new WifiAwareBroadcastReceiver();
            }
            registerWifiIntentReceiver();

            mainHandler.postDelayed(() -> {
                if (!future.isDone()) {
                    future.completeExceptionally(new WiFiAwareException("Initialization timed out"));
                }
            }, 10000);

        } catch (Exception e) {
            Log.e(TAG, "Error initializing WiFi Aware", e);
            future.completeExceptionally(new WiFiAwareException("Initialization error: " + e.getMessage()));
        }

        return future;
    }

    public boolean isWifiAwareAvailable() {
        return wifiAwareManager != null && wifiAwareManager.isAvailable();
    }

    public void addListener(WifiAwareStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(WifiAwareStateListener listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<WifiAwareNetworkInfo> getConnectivityManager(DiscoverySession discoverySession, PeerHandle peerHandle, int port) {
        var future = new CompletableFuture<WifiAwareNetworkInfo>();
        var conInfo = connections.get(peerHandle);
        if (conInfo == null) {
            var newConInfo = new ConnectionInfo();
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

    public WifiAwareSession getWifiAwareSession() {
        return wifiAwareSession;
    }

    public static class WiFiAwareException extends Exception {
        public WiFiAwareException(String message) {
            super(message);
        }
    }

    public Context getContext() {return this.context;}

    public void unregisterWifiIntentReceiver() {
        getContext().unregisterReceiver(receiver);
    }

    public void registerWifiIntentReceiver() {
        getContext().registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    void notifyActionToListeners(WifiAwareHelper.WifiAwareEventType type) {
        notifyActionToListeners(type, null);
    }

    void notifyActionToListeners(WifiAwareHelper.WifiAwareEventType action, String message) {
        var event = new WifiAwareHelper.WifiAwareEvent(action, message);
        for (WifiAwareStateListener listener : listeners) {
            listener.onReceiveAction(event);
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

    public enum WifiAwareEventType {
        WIFI_AWARE_MANAGER_INITIALIZED,
        WIFI_AWARE_MANAGER_AVAILABILITY_CHANGED,
        WIFI_AWARE_MANAGER_FAILED,
        WIFI_AWARE_MANAGER_TERMINATED,
    }
    public record WifiAwareEvent(WifiAwareHelper.WifiAwareEventType type, String message) {}

    public class WifiAwareBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_WIFI_AWARE_STATE_CHANGED.equals(action)) {
                WifiAwareManager manager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                boolean isAvailable = manager != null && manager.isAvailable();

                Log.d(TAG, "WiFi Aware availability changed: " + (isAvailable ? "available" : "unavailable"));

                // If WiFi Aware became unavailable and we had a session, handle session termination
                if (!isAvailable && wifiAwareSession != null) {
                    wifiAwareSession = null;
                    notifyActionToListeners(
                            WifiAwareEventType.WIFI_AWARE_MANAGER_TERMINATED,
                            "WiFi Aware session terminated due to availability change"
                    );
                }

                // Notify listeners about availability change
                notifyActionToListeners(
                        WifiAwareEventType.WIFI_AWARE_MANAGER_AVAILABILITY_CHANGED,
                        "WiFi Aware availability: " + (isAvailable ? "available" : "unavailable")
                );
            }
        }
    }

}

