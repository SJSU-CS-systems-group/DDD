package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.discdd.bundleclient.viewmodels.WifiDirectViewModel;
import net.discdd.wifidirect.WifiDirectManager;

public class BundleClientServiceBroadcastReceiver extends BroadcastReceiver {
    private WifiDirectViewModel viewModel;

    public void setViewModel(WifiDirectViewModel viewModel) {
        this.viewModel = viewModel;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (viewModel == null) return;

        var action = intent.getAction();
        if (action == null) return;
        if (action.equals(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)) {
            String message = intent.getStringExtra("message");
            viewModel.appendResultText(message);
        } else if (action.equals(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION)) {
            var wifiEvent = intent.getSerializableExtra(BundleClientWifiDirectService.WIFI_DIRECT_EVENT_EXTRA,
                                                        WifiDirectManager.WifiDirectEventType.class);
            var bundleClientWifiEvent =
                    intent.getSerializableExtra(BundleClientWifiDirectService.BUNDLE_CLIENT_WIFI_EVENT_EXTRA,
                                                BundleClientWifiDirectService.BundleClientWifiDirectEventType.class);
            if (wifiEvent != null) switch (wifiEvent) {
                case WIFI_DIRECT_MANAGER_INITIALIZED,
                     WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED -> viewModel.updateDeliveryStatus();
                case WIFI_DIRECT_MANAGER_PEERS_CHANGED -> viewModel.updateConnectedDevices();
                case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED,
                     WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED -> viewModel.updateOwnerAndGroupInfo(viewModel.getWifiBgService().getGroupOwnerAddress(),
                                                                                                  viewModel.getWifiBgService().getGroupInfo());
                case WIFI_DIRECT_MANAGER_CONNECTION_CHANGED -> viewModel.discoverPeers();
            }
            if (bundleClientWifiEvent != null) {
                var deviceAddress = intent.getStringExtra(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA);
                switch (bundleClientWifiEvent) {
                    case WIFI_DIRECT_CLIENT_EXCHANGE_STARTED -> {
                        var message = String.format("Exchange started with %s", deviceAddress);
                        viewModel.appendResultText(message);
                    }
                    case WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED -> {
                        var message = String.format("Exchange completed with %s", deviceAddress);
                        viewModel.appendResultText(message);
                    }
                }
            }
        }
    }
}
