package net.discdd.wifiaware;

import android.content.Intent;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.ServiceDiscoveryInfo;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface WifiAwareStateListener {
    void onReceiveAction(WifiAwareHelper.WifiAwareEvent action);

    @Nullable
    IBinder onBind(Intent intent);
}
