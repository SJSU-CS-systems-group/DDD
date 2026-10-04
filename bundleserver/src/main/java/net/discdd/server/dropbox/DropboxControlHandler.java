package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.DropboxControlException;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.InternalAppHandler;
import net.discdd.server.dropbox.DropboxRetentionPlanner.Decision;
import net.discdd.server.dropbox.DropboxRetentionPlanner.Direction;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.InventorySightingRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.compositeId.InventorySightingId;
import net.discdd.server.repository.entity.DropboxNode;
import net.discdd.server.repository.entity.InventorySighting;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * The server end of the dropbox control channel ({@link DropboxControl#APP_ID}). It consumes inventory reports that a
 * DDD Dropbox's embedded client endpoint uploads, asks {@link DropboxRetentionPlanner} which held bundles can go, and
 * queues the answer as a {@link RetentionPlan} ADU in that dropbox's send store, so it rides the dropbox's next
 * downstream bundle ("06 Storage Management Design" § Server).
 * <p>
 * Flow for one accepted report: upsert {@link DropboxNode} (which also adds the control app ID to that client's
 * {@code app_ids.txt}), refresh {@link InventorySighting} first-seen times, plan, then replace any queued plan ADU with
 * the new plan. A report whose {@code reportSeq} is not newer than the stored one is ignored.
 * <p>
 * Timing (Research R6 §3.3 "evaluation timing"): a dropbox uploads its own control bundle in the same carrier sync as
 * the relayed bundles it reports, and the server may process the report first. So {@link #beforeBundleGeneration}
 * re-plans the stored report just before each bundle for the dropbox is built, and queues a fresh plan only when the
 * decision changed; the plan that leaves for the dropbox reflects everything the server knows by then.
 * <p>
 * Dependencies are limited to repositories, {@link AduStores} and an {@code ObjectProvider<ServerSecurity>} (see
 * {@link InternalAppHandler} for the Spring cycle this avoids). The public methods are synchronized so that work on
 * one JVM does not interleave. On a live server every bundle RPC runs inside one transaction ({@code GrpcExecutor}), so
 * the writes made here commit with the RPC that triggered them; the {@code @Transactional} on
 * {@code processReceivedBundle} itself is bypassed by self-invocation (Research R8 §1). Two RPCs racing for the same
 * dropbox can therefore both read the same {@code lastPlanSeq} and queue two plans with one number. That is harmless:
 * every deletion reason is a monotonic fact, the dropbox applies a {@code planSeq} once, and anything a skipped plan
 * would have deleted is re-planned for the next bundle.
 */
@Component
public class DropboxControlHandler implements InternalAppHandler {
    private static final Logger logger = Logger.getLogger(DropboxControlHandler.class.getName());

    private final DropboxNodeRepository dropboxNodeRepository;
    private final InventorySightingRepository inventorySightingRepository;
    private final ReceivedBundleRepository receivedBundleRepository;
    private final BundleMetadataRepository bundleMetadataRepository;
    private final ClientDownstreamAckRepository clientDownstreamAckRepository;
    private final ObjectProvider<ServerSecurity> serverSecurityProvider;
    private final StoreADUs sendFolder;
    private final DropboxRetentionPlanner planner;

    public DropboxControlHandler(DropboxNodeRepository dropboxNodeRepository,
                                 InventorySightingRepository inventorySightingRepository,
                                 ReceivedBundleRepository receivedBundleRepository,
                                 BundleMetadataRepository bundleMetadataRepository,
                                 ClientDownstreamAckRepository clientDownstreamAckRepository,
                                 ObjectProvider<ServerSecurity> serverSecurityProvider,
                                 AduStores aduStores,
                                 @Value("${dropbox.retention.unknown-ttl:14d}") Duration unknownTtl) {
        this.dropboxNodeRepository = dropboxNodeRepository;
        this.inventorySightingRepository = inventorySightingRepository;
        this.receivedBundleRepository = receivedBundleRepository;
        this.bundleMetadataRepository = bundleMetadataRepository;
        this.clientDownstreamAckRepository = clientDownstreamAckRepository;
        this.serverSecurityProvider = serverSecurityProvider;
        this.sendFolder = aduStores.getSendADUsStorage();
        this.planner = new DropboxRetentionPlanner(unknownTtl);
        logger.log(INFO, "Dropbox retention: unknown bundles expire " + unknownTtl + " after first sighting");
    }

    @Override
    public String appId() {
        return DropboxControl.APP_ID;
    }

    /** Only dropboxes get the control app ID; a stock client's app ID list is unchanged. */
    @Override
    public boolean isRegisteredFor(String clientId) {
        return clientId != null && dropboxNodeRepository.existsById(clientId);
    }

    @Override
    public synchronized void handleUpstream(String clientId, List<ADU> adus) throws IOException {
        // A dropbox re-sends unacknowledged control ADUs, so one delivery can hold several reports: keep the newest.
        InventoryReport newest = null;
        String newestJson = null;
        for (var adu : adus) {
            byte[] bytes = Files.readAllBytes(adu.getSource().toPath());
            try {
                var report = DropboxControlCodec.decodeInventory(bytes);
                if (newest == null || report.getReportSeq() > newest.getReportSeq()) {
                    newest = report;
                    newestJson = new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (DropboxControlException e) {
                // the ADU is consumed either way: a bad message will not get better by retrying it
                logger.log(e.getKind() == DropboxControlException.Kind.UNKNOWN_TYPE ? INFO : WARNING,
                           "Dropping control ADU " + adu.getADUId() + " from " + clientId + ": " + e.getKind() + " " +
                                   e.getMessage());
            }
        }
        if (newest == null) return;

        long now = System.currentTimeMillis();
        var node = dropboxNodeRepository.findById(clientId).orElse(null);
        if (node != null && newest.getReportSeq() <= node.lastReportSeq) {
            logger.log(INFO,
                       "Ignoring stale inventory " + newest.getReportSeq() + " from dropbox " + clientId +
                               " (have " + node.lastReportSeq + ")");
            return;
        }
        if (node == null) {
            node = new DropboxNode(clientId, now);
            logger.log(INFO, "Registered dropbox " + clientId);
        }
        node.lastReportSeq = newest.getReportSeq();
        node.lastAppliedPlanSeq = newest.getLastAppliedPlanSeq();
        node.capacityBytes = newest.getCapacityBytes();
        node.usedBytes = newest.getUsedBytes();
        node.lastInventoryJson = newestJson;
        node.lastReportMs = now;
        node = dropboxNodeRepository.save(node);
        logPlanResults(clientId, newest);

        var sightings = refreshSightings(clientId, newest, now);
        var decision = planner.plan(newest, facts(newest, sightings), now);
        // every accepted report gets a plan, even an empty one, so the dropbox learns its report was processed
        enqueuePlan(node, newest, decision, now);
    }

    @Override
    public synchronized void beforeBundleGeneration(String clientId) {
        var node = dropboxNodeRepository.findById(clientId).orElse(null);
        if (node == null || node.lastInventoryJson == null) return;
        try {
            var report = DropboxControlCodec.decodeInventory(node.lastInventoryJson.getBytes(StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            var sightings = inventorySightingRepository.findByIdDropboxId(clientId);
            var decision = planner.plan(report, facts(report, sightings), now);
            if (decision.sameDeletions(decisionOf(node.lastPlanJson))) return;
            logger.log(INFO, "Server facts changed since dropbox " + clientId + "'s last plan; queuing a fresh plan");
            enqueuePlan(node, report, decision, now);
        } catch (DropboxControlException | IOException | RuntimeException e) {
            logger.log(WARNING, "Could not re-plan dropbox " + clientId, e);
        }
    }

    private DropboxRetentionPlanner.Facts facts(InventoryReport report, List<InventorySighting> sightings) {
        return new ServerRetentionFacts(report,
                                        sightings,
                                        receivedBundleRepository,
                                        bundleMetadataRepository,
                                        clientDownstreamAckRepository,
                                        serverSecurityProvider.getIfAvailable());
    }

    /**
     * Records first-seen times for IDs new in this report and forgets IDs it no longer lists. Forgetting only resets
     * the expiry clock; absence from a report never causes a deletion.
     */
    private List<InventorySighting> refreshSightings(String dropboxId, InventoryReport report, long now) {
        var existing = new HashMap<String, InventorySighting>();
        for (var s : inventorySightingRepository.findByIdDropboxId(dropboxId)) {
            existing.put(ServerRetentionFacts.sightingKey(s.id.getDirection(), s.id.getBundleId()), s);
        }
        var current = new ArrayList<InventorySighting>();
        var added = new ArrayList<InventorySighting>();
        var listed = new HashSet<String>();
        report.getUpstream().forEach(e -> sight(dropboxId, Direction.UPSTREAM, e.getBundleId(), now, existing,
                                                 listed, current, added));
        report.getDownstream().forEach(e -> sight(dropboxId, Direction.DOWNSTREAM, e.getBundleId(), now, existing,
                                                   listed, current, added));
        var removed = existing.entrySet()
                .stream()
                .filter(e -> !listed.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!removed.isEmpty()) inventorySightingRepository.deleteAll(removed);
        if (!added.isEmpty()) inventorySightingRepository.saveAll(added);
        return current;
    }

    private static void sight(String dropboxId,
                              Direction direction,
                              String bundleId,
                              long now,
                              Map<String, InventorySighting> existing,
                              Set<String> listed,
                              List<InventorySighting> current,
                              List<InventorySighting> added) {
        String key = ServerRetentionFacts.sightingKey(direction.name(), bundleId);
        if (!listed.add(key)) return;
        var sighting = existing.get(key);
        if (sighting == null) {
            sighting = new InventorySighting(new InventorySightingId(dropboxId, direction.name(), bundleId), now);
            added.add(sighting);
        }
        current.add(sighting);
    }

    /**
     * Queues the plan as the only pending control ADU for the dropbox. Older queued plans are deleted first (the same
     * move as the CLI's message-client delete-all), so only the newest plan rides the next bundle; a plan already sent
     * in an unacknowledged bundle is harmless because plans carry only monotonic facts and the dropbox ignores any
     * {@code planSeq} it has already applied. The sequence number is reserved in the database before the ADU is
     * written, so a crash can leave a gap in the numbering but never reuse a number.
     */
    private void enqueuePlan(DropboxNode node, InventoryReport report, Decision decision, long now) throws IOException {
        long planSeq = node.lastPlanSeq + 1;
        var plan = new RetentionPlan(planSeq,
                                     report.getReportSeq(),
                                     now,
                                     decision.deleteUpstream(),
                                     decision.deleteDownstream(),
                                     null);
        byte[] bytes;
        try {
            bytes = DropboxControlCodec.encode(plan);
        } catch (DropboxControlException e) {
            // cannot happen for a decision built from a valid report (it only shrinks the report's lists)
            logger.log(SEVERE, "Could not encode plan " + planSeq + " for dropbox " + node.clientId, e);
            return;
        }
        node.lastPlanSeq = planSeq;
        node.lastPlanJson = new String(bytes, StandardCharsets.UTF_8);
        node.lastPlanMs = now;
        dropboxNodeRepository.save(node);
        try {
            long lastQueued = sendFolder.getLastADUIdAdded(node.clientId, DropboxControl.APP_ID);
            if (lastQueued > 0) sendFolder.deleteAllFilesUpTo(node.clientId, DropboxControl.APP_ID, lastQueued);
            sendFolder.addADU(node.clientId, DropboxControl.APP_ID, bytes, -1);
        } catch (IOException e) {
            // forget the plan so the next re-plan queues it again instead of thinking it is already queued
            node.lastPlanJson = null;
            dropboxNodeRepository.save(node);
            throw e;
        }
        logger.log(INFO,
                   "Queued plan " + planSeq + " for dropbox " + node.clientId + " (report " + report.getReportSeq() +
                           "): delete " + decision.deleteUpstream().size() + " upstream, " +
                           decision.deleteDownstream().size() + " downstream; keep " + decision.keptUpstream() +
                           " upstream, " + decision.keptDownstream() + " downstream");
    }

    /** the decision a stored plan encodes, or null if there is none or it cannot be read */
    private static Decision decisionOf(String planJson) {
        if (planJson == null) return null;
        try {
            var plan = DropboxControlCodec.decodePlan(planJson.getBytes(StandardCharsets.UTF_8));
            return new Decision(plan.getDeleteUpstream(), plan.getDeleteDownstream(), 0, 0);
        } catch (DropboxControlException e) {
            return null;
        }
    }

    private static void logPlanResults(String dropboxId, InventoryReport report) {
        for (var result : report.getPlanResults()) {
            long failed = result.getSkipped()
                    .stream()
                    .filter(s -> s.getReason() == DropboxControl.SkipReason.DELETE_FAILED)
                    .count();
            logger.log(failed > 0 ? WARNING : INFO,
                       "Dropbox " + dropboxId + " applied plan " + result.getPlanSeq() + ": deleted " +
                               result.getDeleted().size() + ", skipped " + result.getSkipped().size() + " (" + failed +
                               " delete failures)");
        }
    }
}
