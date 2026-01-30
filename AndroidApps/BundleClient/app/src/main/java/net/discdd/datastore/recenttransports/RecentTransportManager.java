package net.discdd.datastore.recenttransports;

import android.content.Context;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.client.bundletransmission.TransportRecord;
import net.discdd.client.bundletransmission.TransportRecordManager;
import net.discdd.grpc.GetRecencyBlobResponse;

import java.util.HashMap;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * Android-specific manager that extends TransportRecordManager with Room persistence.
 */
public class RecentTransportManager extends TransportRecordManager {
    private static final Logger logger = Logger.getLogger(RecentTransportManager.class.getName());
    private RecentTransportRepository repository;

    public RecentTransportManager(Context context) {
        super(new HashMap<>());
        // Initialize Room database and repository for persistent transport storage
        var database = RecentTransportDatabase.getInstance(context);
        this.repository = new RecentTransportRepository(database.recentTransportDao());
        loadPersistedTransports();
    }

    public RecentTransportManager(HashMap<TransportDevice, TransportRecord> recentTransports) {
        super(recentTransports);
    }

    private void loadPersistedTransports() {
        if (repository == null) return;
        try {
            var persisted = repository.loadAll();
            var recentTransports = getRecentTransportsMap();
            synchronized (recentTransports) {
                for (var transport : persisted) {
                    // Create a placeholder device that will be replaced when actually discovered
                    var placeholderDevice = new TransportDevice() {
                        @Override
                        public String getDescription() {
                            return transport.getDescription();
                        }
                        @Override
                        public String getId() {
                            return transport.getTransportId();
                        }
                    };
                    transport.setDevice(placeholderDevice);
                    recentTransports.put(placeholderDevice, transport);
                }
            }
            logger.log(INFO, "Loaded " + persisted.size() + " persisted transports");
        } catch (Exception e) {
            logger.log(WARNING, "Failed to load persisted transports", e);
        }
    }

    @Override
    public void processDiscoveredPeer(TransportDevice device, GetRecencyBlobResponse response) {
        super.processDiscoveredPeer(device, response);
        // Persist the update
        if (repository != null) {
            var recentTransports = getRecentTransportsMap();
            synchronized (recentTransports) {
                var record = recentTransports.get(device);
                if (record != null) {
                    repository.save(RecentTransport.fromTransportRecord(record));
                }
            }
        }
    }

    @Override
    public void expireNotSeenPeers(long expirationTime) {
        super.expireNotSeenPeers(expirationTime);
        // Also delete from database
        if (repository != null) {
            repository.deleteExpired(expirationTime);
        }
    }

    @Override
    public void timestampExchangeWithTransport(TransportDevice device) {
        super.timestampExchangeWithTransport(device);
        // Persist the update
        if (repository != null && device != TransportDevice.SERVER_DEVICE) {
            var recentTransports = getRecentTransportsMap();
            synchronized (recentTransports) {
                var record = recentTransports.get(device);
                if (record != null) {
                    repository.save(RecentTransport.fromTransportRecord(record));
                }
            }
        }
    }
}