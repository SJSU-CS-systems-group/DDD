package net.discdd.client.bundletransmission;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository for managing RecentTransport persistence using Room.
 * Provides async write operations and sync read operations.
 */
public class RecentTransportRepository {
    private static final Logger logger = Logger.getLogger(RecentTransportRepository.class.getName());

    private final RecentTransportDao dao;
    private final ExecutorService executor;

    public RecentTransportRepository(RecentTransportDao dao) {
        this.dao = dao;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Save or update a transport asynchronously.
     */
    public void save(RecentTransport transport) {
        executor.execute(() -> {
            try {
                dao.insertOrUpdate(transport);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to save transport: " + transport.getTransportId(), e);
            }
        });
    }

    /**
     * Load all transports synchronously.
     * Should be called from a background thread.
     */
    public List<RecentTransport> loadAll() {
        return dao.getAllTransports();
    }

    /**
     * Get a specific transport by ID synchronously.
     */
    public RecentTransport getByTransportId(String transportId) {
        return dao.getByTransportId(transportId);
    }

    /**
     * Delete expired transports asynchronously.
     */
    public void deleteExpired(long expirationTime) {
        executor.execute(() -> {
            try {
                dao.deleteExpired(expirationTime);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to delete expired transports", e);
            }
        });
    }

    /**
     * Delete a specific transport asynchronously.
     */
    public void delete(String transportId) {
        executor.execute(() -> {
            try {
                dao.deleteByTransportId(transportId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to delete transport: " + transportId, e);
            }
        });
    }

    /**
     * Update exchange timestamp asynchronously.
     */
    public void updateExchangeTimestamp(String transportId, long timestamp) {
        executor.execute(() -> {
            try {
                dao.updateExchangeTimestamp(transportId, timestamp);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update exchange timestamp for: " + transportId, e);
            }
        });
    }
}
