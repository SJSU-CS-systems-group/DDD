package net.discdd.bundleclient.utils;

import android.app.Application;
import net.discdd.client.bundletransmission.RecencyTracker;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.GetRecencyBlobResponse;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class RecentTransportRepository implements RecencyTracker {
    private final RecentTransportDao recentTransportDao;

    public RecentTransportRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        recentTransportDao = db.recentTransportDao();
    }

    public List<RecentTransport> getAllTransportsSync() {
        return recentTransportDao.getAllTransportsSync();
    }

    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        AppDatabase.runOnDatabaseExecutor(() -> {
            RecentTransport existingTransport = recentTransportDao.getById(device.getId());
            if (existingTransport != null) {
                existingTransport.setDevice(device);
                existingTransport.setLastSeen(System.currentTimeMillis());
                existingTransport.setRecencyBlobResponse(response);
                recentTransportDao.upsert(existingTransport);
            } else {
                RecentTransport newTransport = new RecentTransport();
                newTransport.setTransportId(device.getId());
                newTransport.setDevice(device);
                newTransport.setLastSeen(System.currentTimeMillis());
                newTransport.setRecencyBlobResponse(response);
                recentTransportDao.upsert(newTransport);
            }
        });
    }

    public void timeStampExchange(TransportDevice device) throws ExecutionException, InterruptedException {
        AppDatabase.runOnDatabaseExecutorWithReturn(() -> {
            RecentTransport transport = recentTransportDao.getById(device.getId());
            if (transport != null) {
                transport.setLastExchange(System.currentTimeMillis());
                recentTransportDao.upsert(transport);
                return true;
            }
            return false;
        }).get();
    }

    public void expireNotSeenPeers(long expirationThresholdMillis) {
        AppDatabase.runOnDatabaseExecutor(() -> {
            recentTransportDao.deleteExpired(expirationThresholdMillis);
        });
    }

    // returns true if the blob is more recent than previously seen
    @Override
    public boolean isNewerRecencyBlob(TransportDevice device, GetRecencyBlobResponse response) {
        boolean returnVal = false;
        try {
            returnVal = AppDatabase.runOnDatabaseExecutorWithReturn(() -> {
                RecentTransport existingTransport = recentTransportDao.getById(device.getId());
                long newTimestamp = response.getRecencyBlob().getBlobTimestamp();

                if (existingTransport == null) {
                    // No existing transport record - treat as newer
                    return true;
                }

                long existingTimestamp = existingTransport.getRecencyBlobResponse().getRecencyBlob().getBlobTimestamp();
                return newTimestamp > existingTimestamp;
            }).get();
        } catch (Exception e) {
            returnVal = false;
        }
        return returnVal;
    }

    public void printAllTransports() {
        AppDatabase.runOnDatabaseExecutor(() -> {
            List<RecentTransport> transports = recentTransportDao.getAllTransportsSync();
            if (transports != null && !transports.isEmpty()) {
                for (RecentTransport transport : transports) {
                    System.out.println("Transport ID: " + transport.getTransportId() + ", Last Seen: " + transport.getLastSeen() + ", Last Exchange: " + transport.getLastExchange());
                }
            } else {
                System.out.println("No transports found.");
            }
        });
    }
}
