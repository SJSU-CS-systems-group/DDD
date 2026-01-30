package net.discdd.client.bundletransmission;

import net.discdd.grpc.GetRecencyBlobResponse;

import java.util.HashMap;

/**
 * Manages in-memory transport records.
 * This class has no Room/Android dependencies and can be used in Maven projects.
 * For persistence, use the Android-specific RecentTransportManager which wraps this.
 */
public class TransportRecordManager {
    private final HashMap<TransportDevice, TransportRecord> recentTransports;

    public TransportRecordManager() {
        this.recentTransports = new HashMap<>();
    }

    public TransportRecordManager(HashMap<TransportDevice, TransportRecord> recentTransports) {
        this.recentTransports = recentTransports;
    }

    public HashMap<TransportDevice, TransportRecord> getRecentTransportsMap() {
        return recentTransports;
    }

    public static boolean doesTransportHaveNewData(TransportRecord transport) {
        var response = transport.getRecencyBlobResponse();
        if (response == null) return false;
        return response.getRecencyBlob().getBlobTimestamp() > transport.getLastExchange();
    }

    public TransportRecord[] getRecentTransports() {
        synchronized (recentTransports) {
            return recentTransports.values().toArray(new TransportRecord[0]);
        }
    }

    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        synchronized (recentTransports) {
            TransportRecord record = recentTransports.computeIfAbsent(device, TransportRecord::new);
            record.setDevice(device);
            record.setDescription(device.getDescription());
            record.setLastSeen(System.currentTimeMillis());
            record.setRecencyBlobResponse(response);
        }
    }

    public void expireNotSeenPeers(long expirationTime) {
        synchronized (recentTransports) {
            recentTransports.values().removeIf(transport -> transport.getLastSeen() < expirationTime);
        }
    }

    public void timestampExchangeWithTransport(TransportDevice device) {
        if (device == TransportDevice.SERVER_DEVICE) return;
        synchronized (recentTransports) {
            TransportRecord record = recentTransports.computeIfAbsent(device, TransportRecord::new);
            var now = System.currentTimeMillis();
            record.setLastExchange(now);
            record.setLastSeen(now);
        }
    }
}