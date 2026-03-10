package net.discdd.bundleclient.utils;

import android.app.Application;
import net.discdd.client.bundletransmission.RecencyTracker;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.GetRecencyBlobResponse;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class RecentTransportRepository implements RecencyTracker {
    private final RecentTransportDao recentTransportDao;
    private final HashMap<String, RecentTransport> transportMap = new HashMap<>();

    public RecentTransportRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        recentTransportDao = db.recentTransportDao();
        AppDatabase.runOnDatabaseExecutor(() -> {
            List<RecentTransport> transports = recentTransportDao.getAllTransportsSync();
            synchronized (transportMap)
            {
                if (transports != null) {
                    for (RecentTransport transport : transports) {
                        transportMap.put(transport.getTransportId(), transport);
                    }
                }
            }
        });
    }

    public RecentTransport[] getAllTransports() {
        if (transportMap.isEmpty()) {
            return new RecentTransport[0];
        }
        synchronized(transportMap) {
            return transportMap.values().toArray(new RecentTransport[0]);
        }
    }

    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        synchronized(transportMap) {
            RecentTransport existingTransport = transportMap.get(device.getId());
            if (existingTransport != null) {
                existingTransport.setDevice(device);
                existingTransport.setLastSeen(System.currentTimeMillis());
                existingTransport.setRecencyBlobResponse(response);
            } else {
                RecentTransport newTransport = new RecentTransport();
                newTransport.setTransportId(device.getId());
                newTransport.setDevice(device);
                newTransport.setLastSeen(System.currentTimeMillis());
                newTransport.setRecencyBlobResponse(response);
                transportMap.put(device.getId(), newTransport);
            }
        }
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

    public boolean timeStampExchange(TransportDevice device) {
        synchronized(transportMap) {
            RecentTransport transport = transportMap.get(device.getId());
            if (transport != null) {
                transport.setLastExchange(System.currentTimeMillis());
            }
        }
        try {
            AppDatabase.runOnDatabaseExecutorWithReturn(() -> {
                RecentTransport transport = recentTransportDao.getById(device.getId());
                if (transport != null) {
                    transport.setLastExchange(System.currentTimeMillis());
                    recentTransportDao.upsert(transport);
                    return true;
                }
                return false;
            }).get();
        } catch (ExecutionException | InterruptedException e) {
            return false;
        }
        return true;
    }

    public void expireNotSeenPeers(long expirationThresholdMillis) {
        synchronized(transportMap) {
            transportMap.entrySet().removeIf(entry -> entry.getValue().getLastSeen() < expirationThresholdMillis);
        }
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
                    return false;
                }
                long existingTimestamp = existingTransport.getRecencyBlobResponse().getRecencyBlob().getBlobTimestamp();
                return newTimestamp > existingTimestamp;
            }).get();
        } catch (Exception e) {
            return false;
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
