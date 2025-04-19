package net.discdd.bundletransport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.discdd.bundletransport.viewmodels.WifiDirectViewModel;
import net.discdd.wifidirect.WifiDirectManager;

public class BundleTransportWifiEvent extends BroadcastReceiver {
    private WifiDirectViewModel viewModel;

    public void setViewModel(WifiDirectViewModel viewModel) {
        this.viewModel = viewModel;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null &&
                intent.getAction().equals(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION)) {
            String message = intent.getStringExtra("message");
            viewModel.appendToClientLog(message);
        } else {
            var actionType = intent.getSerializableExtra("type", WifiDirectManager.WifiDirectEventType.class);
            var actionMessage = intent.getStringExtra("message");
            if (actionType != null) {
                switch (actionType) {
                    case WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED:
                    case WIFI_DIRECT_MANAGER_INITIALIZED:
                        viewModel.processDeviceInfoChange();
                        viewModel.updateGroupInfo();
                        break;
                    case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED:
                        viewModel.updateGroupInfo();
                        break;
                }
            }
        }
    }
}
