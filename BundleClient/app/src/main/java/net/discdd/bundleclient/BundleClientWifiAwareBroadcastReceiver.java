package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.discdd.bundleclient.viewmodels.WifiAwareViewModel;
import net.discdd.wifiaware.WifiAwareHelper;

import java.util.logging.Level;
import java.util.logging.Logger;

public class BundleClientWifiAwareBroadcastReceiver extends BroadcastReceiver {
    private static final Logger logger = Logger.getLogger(BundleClientWifiAwareBroadcastReceiver.class.getName());
    private WifiAwareViewModel viewModel;

    public void setViewModel(WifiAwareViewModel viewModel) {
        this.viewModel = viewModel;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (viewModel == null) {
            logger.warning("WifiAwareViewModel is null, cannot process intent");
            return;
        }

        String action = intent.getAction();
        if (action == null) return;

        try {
            if (action.equals(BundleClientWifiAwareService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)) {
                // Handle log messages
                String message = intent.getStringExtra("message");
                viewModel.appendResultText(message);
            } else if (action.equals(BundleClientWifiAwareService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION)) {
                // Handle WiFi Aware events
                WifiAwareHelper.WifiAwareEventType wifiEvent =
                        (WifiAwareHelper.WifiAwareEventType) intent.getSerializableExtra(
                                BundleClientWifiAwareService.WIFI_AWARE_EVENT_EXTRA
                        );

                if (wifiEvent != null) {
                    String serviceType = intent.getStringExtra("serviceType");
                    handleWifiAwareEvent(context, wifiEvent, serviceType);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing broadcast intent", e);
        }
    }

    private void handleWifiAwareEvent(Context context, WifiAwareHelper.WifiAwareEventType event, String serviceType) {
        switch (event) {
            case WIFI_AWARE_MANAGER_INITIALIZED:
                viewModel.onWifiAwareInitialized();
                break;
            case WIFI_AWARE_MANAGER_AVAILABILITY_CHANGED:
                viewModel.handleWifiAwareAvailabilityChange(context);
                break;
            case WIFI_AWARE_MANAGER_FAILED:
                viewModel.onWifiAwareInitializationFailed();
                break;
            case WIFI_AWARE_MANAGER_TERMINATED:
                viewModel.onWifiAwareSessionTerminated();
                break;
            default:
                logger.fine("Unhandled WiFi Aware event: " + event);
        }
    }
}