package net.discdd.datastore;

import android.app.Application;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.client.bundletransmission.TransportRepository;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RecentTransportRepository implements TransportRepository {
    private static final Logger logger = Logger.getLogger(RecentTransportRepository.class.getName());
    private final RecentTransportDao transportDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RecentTransportRepository(Application application) {
        ClientDataBase db = ClientDataBase.getInstance(application);
        transportDao = db.transportDao();
    }

    @Override
    public boolean isRecencyBlobNew(TransportDevice device, RecencyBlob recencyBlob) {
        Future<Boolean> future = executor.submit(() -> {
            RecentTransport transport = transportDao.getByTransportId(device.getId());
            transport = (transport == null) ? new RecentTransport(device) : transport;
            if (recencyBlob.getBlobTimestamp() > transport.getRecencyTime()) {
                transport.setRecencyTime(recencyBlob.getBlobTimestamp());
                transportDao.insertOrUpdate(transport);
                return true;
            }
            return false;
        });
        return getResult(future, false);
    }

    @Override
    public void timestampExchange(TransportDevice device) {
        if (device == TransportDevice.SERVER_DEVICE) return;
        executor.execute(() -> {
            RecentTransport transport = transportDao.getByTransportId(device.getId());
            transport = (transport == null) ? new RecentTransport(device) : transport;
            var now = System.currentTimeMillis();
            transport.setLastExchange(now);
            transport.setLastSeen(now);
            transportDao.insertOrUpdate(transport);
        });
    }

    public static boolean doesTransportHaveNewData(RecentTransport transport) {
        var response = transport.getRecencyBlobResponse();
        if (response == null) {
            return false;
        }
        var blob = response.getRecencyBlob();
        return blob.getBlobTimestamp() > transport.getLastExchange();
    }

    public RecentTransport[] getRecentTransports() {
        Future<RecentTransport[]> future = executor.submit(() ->
                transportDao.getAllTransports().toArray(new RecentTransport[0])
        );
        return getResult(future, new RecentTransport[0]);
    }

    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        executor.execute(() -> {
            RecentTransport transport = transportDao.getByTransportId(device.getId());
            transport = (transport == null) ? new RecentTransport(device, response) : transport;
            transport.setDevice(device);
            transport.setDeviceName(device.getDescription());
            transport.setLastSeen(System.currentTimeMillis());
            transport.setRecencyBlobResponse(response);
            transportDao.insertOrUpdate(transport);
        });
    }

    public void expireNotSeenPeers(long expirationTime) {
        executor.execute(() -> {
            var now = System.currentTimeMillis();
            transportDao.getAllTransports().stream()
                    .filter(transport -> transport.getLastSeen() < now - expirationTime)
                    .forEach(transport -> transportDao.deleteByTransportId(transport.getTransportId()));
        });
    }

    private <T> T getResult(Future<T> future, T defaultValue) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "Database operation failed", e);
            return defaultValue;
        }
    }
}
