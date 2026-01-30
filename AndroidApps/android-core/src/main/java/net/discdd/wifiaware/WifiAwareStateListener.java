package net.discdd.wifiaware;

import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;

public interface WifiAwareStateListener {
    void onReceiveAction(WifiAwareHelper.WifiAwareEvent action);

    @Nullable
    IBinder onBind(Intent intent);
}
