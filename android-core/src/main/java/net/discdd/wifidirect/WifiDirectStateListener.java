package net.discdd.wifidirect;
public interface WifiDirectStateListener {
    void onReceiveAction(WifiDirectManager.WifiDirectEvent action);
}