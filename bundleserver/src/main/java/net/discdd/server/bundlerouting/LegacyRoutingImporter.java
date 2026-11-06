package net.discdd.server.bundlerouting;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.server.repository.RoutingMetaRepository;
import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.entity.RoutingMeta;
import net.discdd.server.repository.entity.ServerRouting;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * Converts the legacy {@code serverroutingtable} into UPLOADED {@code routing_edge} rows once, at startup, so routes
 * learned before this server version keep delivering.
 * <p>
 * A legacy row's {@code transportID} is either a TLS name ({@code DDDTLSUtil.publicKeyToName}: unpadded Base64url of
 * the X.509 DER) or a key from old client metadata (a 28-character SHA-1 ID, or junk). Only TLS names are converted:
 * {@code SecurityUtils.generateID(name)} is exactly {@code SecurityUtils.peerIdForTlsKey} of that key. Every
 * converted edge is dated now, so it lives one TTL. The legacy table is left untouched, so the previous server
 * version can still run on it; a {@link RoutingMeta#LEGACY_IMPORT_DONE_MS} row stops a second import. A failed import
 * writes no marker and is retried at the next startup.
 * <p>
 * Order: Spring Boot sorts {@code ApplicationRunner} and {@code CommandLineRunner} beans together by {@code @Order}.
 * {@link #RUNNER_ORDER} puts the import after {@code CLRunner} ({@code @Order(0)}, which exits for CLI commands, so
 * a CLI process never imports) and strictly before the unordered {@code GrpcServerRunner}
 * ({@link Ordered#LOWEST_PRECEDENCE}). So the import is done before any upload can write routing rows: its UPLOADED
 * rows are the only UPLOADED rows written outside a client's receive lock (the ADU deliverer's startup pass may also
 * write DEPOSITED rows before gRPC starts; see {@link BundleRouting}), and a colliding insert would fail an upload's
 * commit after the transport already deleted its copy.
 */
@Component
@Order(LegacyRoutingImporter.RUNNER_ORDER)
public class LegacyRoutingImporter implements ApplicationRunner {
    private static final Logger logger = Logger.getLogger(LegacyRoutingImporter.class.getName());
    /** runs before {@code GrpcServerRunner}, which has no {@code @Order}; see the class comment */
    static final int RUNNER_ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    private final ServerRoutingRepository serverRoutingRepository;
    private final RoutingMetaRepository routingMetaRepository;
    private final BundleRouting bundleRouting;
    private final TransactionTemplate transactionTemplate;

    public LegacyRoutingImporter(ServerRoutingRepository serverRoutingRepository,
                                 RoutingMetaRepository routingMetaRepository,
                                 BundleRouting bundleRouting,
                                 PlatformTransactionManager transactionManager) {
        this.serverRoutingRepository = serverRoutingRepository;
        this.routingMetaRepository = routingMetaRepository;
        this.bundleRouting = bundleRouting;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            transactionTemplate.executeWithoutResult(status -> importOnce(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            logger.log(WARNING, "Legacy routing import failed; it will be retried at the next startup", e);
        }
    }

    /** imports the legacy rows unless the marker exists; returns the number of rows converted */
    public int importOnce(long nowMs) {
        if (routingMetaRepository.existsById(RoutingMeta.LEGACY_IMPORT_DONE_MS)) return 0;
        int converted = 0;
        int skipped = 0;
        for (ServerRouting row : serverRoutingRepository.findAll()) {
            String transportId = row.getServerRoutingId().getTransportID();
            if (isTlsName(transportId)) {
                bundleRouting.recordUploaded(SecurityUtils.generateID(transportId),
                                             row.getServerRoutingId().getClientID(),
                                             nowMs);
                converted++;
            } else {
                skipped++;
            }
        }
        routingMetaRepository.save(new RoutingMeta(RoutingMeta.LEGACY_IMPORT_DONE_MS, nowMs));
        logger.log(INFO,
                   "Legacy routing import: converted " + converted + " TLS-name rows, skipped " + skipped +
                           " metadata or unparseable rows");
        return converted;
    }

    /** true if {@code transportId} is Base64url of an X.509 EC public key (a {@code publicKeyToName} name) */
    static boolean isTlsName(String transportId) {
        if (transportId == null || transportId.isEmpty()) return false;
        try {
            byte[] der = Base64.getUrlDecoder().decode(transportId);
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            return true;
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }
    }
}
