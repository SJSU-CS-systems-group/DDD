package com.ddd.wifidirect;

public interface WifiDirectStateListener {
    void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action);
}