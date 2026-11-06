package net.discdd.server.bundlerouting;

import net.discdd.server.repository.RoutingEdgeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * Deletes routing edges last seen more than twice {@code routing.ttl} ago, every {@code routing.sweep-interval}, on
 * its own thread and in its own transactions (batches of {@value #BATCH}), so the RPC hot path never deletes. Rows
 * between one and two TTLs old are already ignored by every read.
 */
@Component
public class RoutingSweeper {
    private static final Logger logger = Logger.getLogger(RoutingSweeper.class.getName());
    static final int BATCH = 500;

    private final RoutingEdgeRepository routingEdgeRepository;
    private final TransactionTemplate transactionTemplate;
    private final long ttlMs;
    private final Duration sweepInterval;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "routing-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    public RoutingSweeper(RoutingEdgeRepository routingEdgeRepository,
                          PlatformTransactionManager transactionManager,
                          @Value("${routing.ttl:14d}") Duration ttl,
                          @Value("${routing.sweep-interval:6h}") Duration sweepInterval) {
        this.routingEdgeRepository = routingEdgeRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ttlMs = ttl.toMillis();
        this.sweepInterval = sweepInterval;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep(System.currentTimeMillis());
            } catch (RuntimeException e) {
                // an escaping exception would cancel every later run
                logger.log(WARNING, "Routing sweep failed", e);
            }
        }, sweepInterval.toMillis(), sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** deletes every edge last seen before {@code nowMs - 2 * ttl}; returns how many were deleted */
    public int sweep(long nowMs) {
        long cutoff = nowMs - 2 * ttlMs;
        int total = 0;
        int batchSize;
        do {
            int[] batch = transactionTemplate.execute(status -> {
                var ids = routingEdgeRepository.findIdsLastSeenBefore(cutoff, PageRequest.of(0, BATCH));
                int deleted = 0;
                for (var id : ids) {
                    deleted += routingEdgeRepository.deleteIfLastSeenBefore(id.peerId, id.clientId, id.source, cutoff);
                }
                return new int[] { ids.size(), deleted };
            });
            batchSize = batch[0];
            total += batch[1];
        } while (batchSize == BATCH);
        if (total > 0) logger.log(INFO, "Routing sweep deleted " + total + " edges last seen before " + cutoff);
        return total;
    }
}
