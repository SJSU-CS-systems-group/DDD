package net.discdd.bundleclient.service.wifiDirect;

import net.discdd.bundleclient.service.DDDWifiConnection;

import java.net.InetAddress;
import java.util.List;

public class DDDWifiDirectConnection implements DDDWifiConnection {
    DDDWifiDirectDevice dev;
    public DDDWifiDirectConnection(DDDWifiDirectDevice dev) {
        this.dev = dev;
    }

    @Override
    public List<InetAddress> getAddresses() {
        return dev.getInetAddresses();
    }

    @Override
    public String getConnectionDescription() {
        return dev.getDescription();
    }
}
