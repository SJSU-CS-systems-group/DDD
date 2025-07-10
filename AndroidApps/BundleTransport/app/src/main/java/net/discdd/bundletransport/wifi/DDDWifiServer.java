package net.discdd.bundletransport.wifi;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.lifecycle.LiveData;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.StateFlow;
import net.discdd.bundletransport.service.DDDWifiServiceEvents;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class DDDWifiServer {
    private static final Logger logger = Logger.getLogger(DDDWifiServer.class.getName());
    private final WifiDirectManager wifiDirectManager;

    public DDDWifiServer(Context applicationContext) {
        wifiDirectManager = new WifiDirectManager(applicationContext, new DDDWifiDirectStateListener(), true);
    }

    public DDDWifiNetworkInfo getNetworkInfo() {
        var gi = wifiDirectManager.getGroupInfo();
        if (gi == null) {
            return null;
        }
        InetAddress inetAddress;

        try {
            var ni = NetworkInterface.getByName(gi.getInterface());
            inetAddress = ni == null ?
                          null :
                          Collections.list(ni.getInetAddresses())
                                  .stream()
                                  .filter(a -> a instanceof Inet4Address)
                                  .findFirst()
                                  .orElse(null);
        } catch (Exception e) {
            logger.severe("Error getting network interface: " + e.getMessage());
            inetAddress = null;
        }
        var clientList = gi.getClientList().stream().map(d -> d.deviceName).collect(Collectors.toUnmodifiableList());
        return new DDDWifiNetworkInfo(gi.getNetworkName(), gi.getPassphrase(), inetAddress, clientList);
    }

    // TODO
    public void shutdown() {}

    public String getDeviceName() {
        return wifiDirectManager.getDeviceName();
    }

    @SuppressLint("MissingPermission")
    public void initialize() {
        wifiDirectManager.initialize();
    }

    public WifiDirectManager.WifiDirectStatus getStatus() {
        return wifiDirectManager.getStatus();
    }

    public enum DDDWifiServerEventType {
        DDDWIFISERVER_NETWORKINFO_CHANGED, DDDWIFISERVER_DEVICENAME_CHANGED, DDDWIFISERVER_MESSAGE
    }

    public static class DDDWifiServerEvent {
        public final DDDWifiServerEventType type;
        public final String data;

        public DDDWifiServerEvent(DDDWifiServerEventType type, String data) {
            this.type = type;
            this.data = data;
        }
    }

    public static class DDDWifiNetworkInfo {
        public final String ssid;
        public final String password;
        public final InetAddress inetAddress;
        public final List<String> clientList;

        public DDDWifiNetworkInfo(String ssid, String password, InetAddress inetAddress, List<String> clientList) {
            this.ssid = ssid;
            this.password = password;
            this.inetAddress = inetAddress;
            this.clientList = clientList;
        }
    }

    class DDDWifiDirectStateListener implements WifiDirectStateListener {
        @Override
        public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
            switch (action.type()) {
                case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED:
                    DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_NETWORKINFO_CHANGED,
                                                                                         null));
                    var groupInfo = wifiDirectManager.getGroupInfo();
                    DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_MESSAGE,
                                                                    "Group info: " + (groupInfo == null ?
                                                                                      "N/A" :
                                                                                      groupInfo.getClientList()
                                                                                              .stream()
                                                                                              .map(d -> d.deviceName)
                                                                                              .collect(Collectors.joining(
                                                                                                      ", ")))));
                case WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED:
                    DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_DEVICENAME_CHANGED,
                                                                    wifiDirectManager.getDeviceName()));
                    break;
            }
        }
    }
}
