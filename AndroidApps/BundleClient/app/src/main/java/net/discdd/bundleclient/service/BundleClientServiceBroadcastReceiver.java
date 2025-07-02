package net.discdd.bundleclient.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.discdd.bundleclient.viewmodels.WifiDirectViewModel;

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
        if (action.equals(BundleClientService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)) {
            String message = intent.getStringExtra("message");
            viewModel.appendResultText(message);
        } else if (action.equals(BundleClientService.NET_DISCDD_BUNDLECLIENT_WIFI_ACTION)) {
            // we might be betting a wifi event or an event about a transmission.
            // we distinguish them by the presence of a specific extra.
            var wifiEvent = intent.getSerializableExtra(BundleClientService.DDDWIFI_EVENT_EXTRA,
                                                        DDDWifiEventType.class);
            var bundleClientTransmissionEvent =
                    intent.getSerializableExtra(BundleClientService.BUNDLE_CLIENT_TRANSMISSION_EVENT_EXTRA,
                                                BundleClientService.BundleClientTransmissionEventType.class);
            if (wifiEvent != null) switch (wifiEvent) {
                case DDDWIFI_DISCOVERY_CHANGED, DDDWIFI_STATE_CHANGED -> viewModel.updateState();
                case DDDWIFI_PEERS_CHANGED -> viewModel.updateConnectedDevices();
            }
            if (bundleClientTransmissionEvent != null) {
                // var deviceAddress = intent.getStringExtra(BundleClientService.NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA);
                switch (bundleClientTransmissionEvent) {
                    case WIFI_DIRECT_CLIENT_EXCHANGE_STARTED -> {
                    }
                    case WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED -> {
                    }
                }
            }
        }
    }
}
