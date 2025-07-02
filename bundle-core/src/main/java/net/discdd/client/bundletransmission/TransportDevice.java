package net.discdd.client.bundletransmission;

/**
 * Represents a transport device used for data transmission.
 * In practice these will all be DDDWIfiDevices, but DDDWifiDevice is not available outside
 * of Android.
 */
public interface TransportDevice {
    String getDescription();



    static TransportDevice SERVER_DEVICE = new FakeDevice("Server");
    static TransportDevice FAKE_DEVICE = new FakeDevice("Fake");
}

class FakeDevice implements TransportDevice {
    private final String description;

    public FakeDevice(String description) {
        this.description = description;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
