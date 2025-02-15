package net.discdd.wifidirect;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public interface WifiDirectStateListener {
    void onReceiveAction(WifiDirectManager.WifiDirectEvent action);
}