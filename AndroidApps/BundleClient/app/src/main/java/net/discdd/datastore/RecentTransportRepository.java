package net.discdd.datastore;

import android.app.Application;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.GetRecencyBlobResponse;

public class RecentTransportRepository {
    private RecentTransportDao transportDao;

    public RecentTransportRepository(Application application) {
        ClientDataBase db = ClientDataBase.getInstance(application);
        transportDao = db.transportDao();
    }

    public static boolean doesTransportHaveNewData(RecentTransport transport) {
        return transport.getRecencyBlobResponse().getRecencyBlob().getBlobTimestamp() > transport.getLastExchange();
    }

    public RecentTransport[] getRecentTransports() {
        RecentTransport[] allTransports = transportDao.getAllTransports().toArray(new RecentTransport[0]);
        return java.util.Arrays.stream(allTransports)
                .toArray(RecentTransport[]::new);
    }

    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        RecentTransport transport = transportDao.getByTransportId(device.getId());
        transport = (transport == null) ? new RecentTransport(device, response) : transport;
        transport.setDevice(device);
        transport.setLastSeen(System.currentTimeMillis());
        transport.setRecencyBlobResponse(response);
    }

    public void timestampExchange(TransportDevice device) {
        if (device == TransportDevice.SERVER_DEVICE) return;
        RecentTransport transport = transportDao.getByTransportId(device.getId());
        transport = (transport == null) ? new RecentTransport(device) : transport;
        var now = System.currentTimeMillis();
        transport.setLastExchange(now);
        transport.setLastSeen(now);
    }

    public void expireNotSeenPeers(long expirationTime) {
        var now = System.currentTimeMillis();
        transportDao.getAllTransports().stream()
                .filter(transport -> transport.getLastSeen() < now - expirationTime)
                .forEach(transport -> transportDao.deleteByTransportId(transport.getTransportId()));
    }
}
