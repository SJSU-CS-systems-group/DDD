package net.discdd.bundleclient.service;

import java.net.InetAddress;
import java.util.List;

public interface DDDWifiConnection {
    List<InetAddress> getAddresses();

    String getConnectionDescription();
}
