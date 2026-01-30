package net.discdd.client.bundletransmission;

/**
 * Represents a transport device used for data transmission.
 * In practice these will all be DDDWIfiDevices, but DDDWifiDevice is not available outside
 * of Android.
 */
public interface TransportDevice {
    static TransportDevice SERVER_DEVICE = new FakeDevice("Server");
    static TransportDevice FAKE_DEVICE = new FakeDevice("Fake");

    String getDescription();

    String getId();
}

class FakeDevice implements TransportDevice {
    private final String description;

    public FakeDevice(String description) {
        this.description = description;
    }

    @Override
    public String getDescription() { return description; }

    @Override
    public String getId() { return description; }
}
