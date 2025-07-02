package net.discdd.bundleclient.service.wifiDirect;

import net.discdd.bundleclient.service.DDDWifiConnection;
import net.discdd.bundleclient.service.DDDWifiDevice;

import java.net.InetAddress;
import java.util.List;

public class DDDWifiDirectConnection implements DDDWifiConnection {
    private final InetAddress addr;
    DDDWifiDirectDevice dev;
    public DDDWifiDirectConnection(DDDWifiDirectDevice dev, InetAddress addr) {
        this.dev = dev;
        this.addr = addr;
    }

    @Override
    public List<InetAddress> getAddresses() {
        return List.of(addr);
    }

    @Override
    public DDDWifiDevice getDevice() {
        return dev;
    }
}
