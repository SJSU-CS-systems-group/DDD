package net.discdd.bundleclient.service.wifiDirect;

import android.Manifest;
import androidx.annotation.RequiresPermission;
import net.discdd.bundleclient.service.BundleClientService;
import net.discdd.bundleclient.service.DDDWifi;
import net.discdd.bundleclient.service.DDDWifiConnection;
import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.bundleclient.service.DDDWifiEventType;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class DDDWifiDirect implements DDDWifi, WifiDirectStateListener {
    private static final Logger logger = Logger.getLogger(DDDWifiDirect.class.getName());
    final private WifiDirectManager wifiDirectManager;
    final private BundleClientService bundleClientService;

    public DDDWifiDirect(BundleClientService bundleClientService) {
        this.bundleClientService = bundleClientService;
        wifiDirectManager = new WifiDirectManager(bundleClientService, this, false);
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    public void initialize() {
        wifiDirectManager.initialize();
    }

    @Override
    public CompletableFuture<Void> startDiscovery() {
        return wifiDirectManager.discoverPeers().thenAccept(v -> {});
    }

    @Override
    public boolean isDiscoveryActive() {
        return wifiDirectManager.isDiscoveryActive();
    }

    @Override
    public String getStateDescription() {
        var sb = new StringBuilder();
        var groupInfo = wifiDirectManager.getGroupInfo();
        if (groupInfo != null) {
            sb.append("Connected to ").append(groupInfo.getOwner().deviceName);
        } else {
            sb.append("Idle");
        }
        return sb.toString();
    }

    @Override
    public boolean isDddWifiEnabled() {
        return wifiDirectManager.getWifiDirectEnabled();
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    @Override
    public CompletableFuture<DDDWifiConnection> connectTo(DDDWifiDevice dev) {
        DDDWifiDirectDevice wifiDirectDevice = (DDDWifiDirectDevice) dev;
        return wifiDirectManager.connect(wifiDirectDevice.wifiP2pDevice).thenApply(g -> {
            var addr = wifiDirectManager.getGroupOwnerAddress();
            List<InetAddress> addresses = addr == null ? List.of() : List.of(addr);
            wifiDirectDevice.setAddresses(addresses);
            return new DDDWifiDirectConnection(wifiDirectDevice);
        });
    }

    @Override
    public List<DDDWifiDevice> listDevices() {
        return Collections.emptyList();
    }

    @Override
    public CompletableFuture<Void> disconnectFrom(DDDWifiDevice dev) {
        return wifiDirectManager.disconnect().thenAccept(b -> {});
    }

    public void shutdown() {
        wifiDirectManager.shutdown();
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        var dddEventType = switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZED -> DDDWifiEventType.DDDWIFI_INITIALIZED;
            case WIFI_DIRECT_MANAGER_PEERS_CHANGED -> DDDWifiEventType.DDDWIFI_PEERS_CHANGED;
            case WIFI_DIRECT_MANAGER_CONNECTION_CHANGED, WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED,
                 WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED -> DDDWifiEventType.DDDWIFI_STATE_CHANGED;
            case WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED -> DDDWifiEventType.DDDWIFI_DISCOVERY_CHANGED;
            case WIFI_DIRECT_MANAGER_SHUTDOWN -> DDDWifiEventType.DDDWIFI_SHUTDOWN;
        };
        bundleClientService.broadcastWifiEvent(dddEventType);
    }
}
