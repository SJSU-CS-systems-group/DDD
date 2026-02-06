package net.discdd.datastore;

import androidx.room.TypeConverter;
import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.bundleclient.service.wifiDirect.DDDWifiDirectDevice;
import net.discdd.client.bundletransmission.TransportDevice;

public class DeviceConverter {
    @TypeConverter
    public static String fromTransportDevice(TransportDevice device) {
        return device == null ? null : device.getId();
    }

    @TypeConverter
    public static TransportDevice toTransportDevice(String deviceId) {
        return deviceId == null ? null : new DDDWifiDirectDevice(deviceId) {};
    }
}
