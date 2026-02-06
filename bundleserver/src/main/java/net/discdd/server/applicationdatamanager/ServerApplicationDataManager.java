package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientBundleCountersRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.entity.BundleMetadata;
import net.discdd.server.repository.entity.ClientBundleCounters;
import net.discdd.server.repository.entity.SentAduDetails;
import net.discdd.utils.StoreADUs;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Service
public class ServerApplicationDataManager {
    private static final Logger logger = Logger.getLogger(ServerApplicationDataManager.class.getName());
    private final BundleServerConfig bundleServerConfig;
    private final SentAduDetailsRepository sentAduDetailsRepository;
    private final BundleMetadataRepository bundleMetadataRepository;
    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;
    private final ClientBundleCountersRepository clientBundleCountersRepository;
    AduDeliveredListener aduDeliveredListener;
    private final StoreADUs receiveADUsStorage;
    private final StoreADUs sendADUsStorage;

    public ServerApplicationDataManager(AduStores aduStores,
                                        AduDeliveredListener aduDeliveredListener,
                                        SentAduDetailsRepository sentAduDetailsRepository,
                                        BundleMetadataRepository bundleMetadataRepository,
                                        RegisteredAppAdapterRepository registeredAppAdapterRepository,
                                        ClientBundleCountersRepository clientBundleCountersRepository,
                                        BundleServerConfig bundleServerConfig) {
        this.aduDeliveredListener = aduDeliveredListener;
        this.clientBundleCountersRepository = clientBundleCountersRepository;
        this.sentAduDetailsRepository = sentAduDetailsRepository;
        this.bundleMetadataRepository = bundleMetadataRepository;
        this.bundleServerConfig = bundleServerConfig;
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        this.sendADUsStorage = aduStores.getSendADUsStorage();
        this.receiveADUsStorage = aduStores.getReceiveADUsStorage();
    }

    public List<String> getRegisteredAppIds() {
        return registeredAppAdapterRepository.findAllAppIds().stream().toList();
    }

    public void processAcknowledgement(String clientId, String bundleId) throws IOException {
        if ("HB".equals(bundleId)) {
            return;
        }

        List<SentAduDetails> sentAduDetailsList = this.sentAduDetailsRepository.findByBundleId(bundleId);

        for (SentAduDetails sentAduDetails : sentAduDetailsList) {
            String appId = sentAduDetails.appId;
            Long lastAduIdForAppId = sentAduDetails.aduIdRangeEnd;
            sendADUsStorage.deleteAllFilesUpTo(clientId, appId, lastAduIdForAppId);
            logger.log(INFO,
                       "[DataStoreAdaptor] Deleted ADUs for application " + appId + " with id upto " +
                               lastAduIdForAppId);
        }

        sentAduDetailsRepository.deleteByBundleId(bundleId);

        logger.log(INFO,
                   "[StateManager] Processed acknowledgement and deleted bundle id " + bundleId +
                           " corresponding to client " + clientId);
    }

    public void storeReceivedADUs(String clientId, String bundleId, long receivedBundleCounter, List<ADU> adus) throws
            IOException {
        logger.log(INFO, "[ApplicationDataManager] Store ADUs");

        updateLastReceivedCounter(clientId, receivedBundleCounter, bundleId);
        logger.log(INFO, "[StateManager] Registered bundle identifier: " + bundleId + " of client " + clientId);
        var affectedAppIds = new HashSet<String>();

        Map<String, List<ADU>> appIdToADUMap = new HashMap<>();
        for (var adu : adus) {
            var addedFile = receiveADUsStorage.addADU(clientId,
                                                      adu.getAppId(),
                                                      Files.readAllBytes(adu.getSource().toPath()),
                                                      adu.getADUId());
            if (addedFile != null) affectedAppIds.add(adu.getAppId());
        }
        aduDeliveredListener.onAduDelivered(clientId, affectedAppIds);
    }

    private void updateLastReceivedCounter(String clientId, long receivedBundleCounter, String receivedBundleId) {
        var counters = getBundleCountersForClient(clientId);
        if (counters.lastReceivedBundleCounter < receivedBundleCounter) {
            counters.lastReceivedBundleCounter = receivedBundleCounter;
            counters.lastReceivedBundleId = receivedBundleId;
            clientBundleCountersRepository.save(counters);
        }
    }

    public ClientBundleCounters getBundleCountersForClient(String clientId) {
        return clientBundleCountersRepository.findById(clientId)
                .orElse(new ClientBundleCounters(clientId, 0, "", 0, ""));
    }

    private void updateLastSentCounter(String clientId, long sentBundleCounter, String sentBundleId) {
        var counters = getBundleCountersForClient(clientId);
        if (counters.lastSentBundleCounter < sentBundleCounter) {
            counters.lastSentBundleCounter = sentBundleCounter;
            counters.lastSentBundleId = sentBundleId;
            clientBundleCountersRepository.save(counters);
        }
    }

    static class SizeLimiter implements Predicate<Long> {
        long remaining;

        SizeLimiter(long dataSizeLimit) {
            remaining = dataSizeLimit;
        }

        @Override
        public boolean test(Long size) {
            remaining -= size;
            return remaining >= 0;
        }
    }

    public List<ADU> fetchADUsToSend(String bundleId, long bundleCounter, long initialSize, String clientId) throws
            IOException {
        List<ADU> adusToSend = new ArrayList<>();

        final long dataSizeLimit = this.bundleServerConfig.getApplicationDataManager().getAppDataSizeLimit();
        var sizeLimiter = new SizeLimiter(dataSizeLimit - initialSize);
        for (String appId : this.getRegisteredAppIds()) {
            var sentAdus = new SentAduDetails();
            sentAdus.appId = appId;
            sentAdus.bundleId = bundleId;
            sentAdus.ClientBundleCounter = bundleCounter;
            sendADUsStorage.getADUs(clientId, appId).takeWhile(a -> sizeLimiter.test(a.getSize())).peek(adu -> {
                if (adu.getADUId() > sentAdus.aduIdRangeEnd) {
                    sentAdus.aduIdRangeEnd = adu.getADUId();
                }
                if (adu.getADUId() < sentAdus.aduIdRangeStart || sentAdus.aduIdRangeStart == 0) {
                    sentAdus.aduIdRangeStart = adu.getADUId();
                }
            }).forEach(adusToSend::add);
            if (sentAdus.aduIdRangeEnd > 0) {
                sentAduDetailsRepository.save(sentAdus);
            }
        }
        return adusToSend;
    }

    /**
     * Generate a new bundle and return the newly generated encrypted bundleId.
     */
    public void registerNewBundleId(String clientId, String encryptedBundleId, long bundleCounter, long ackCounter) {
        var counters = getBundleCountersForClient(clientId);
        counters.lastSentBundleId = encryptedBundleId;
        counters.lastSentBundleCounter = bundleCounter;
        clientBundleCountersRepository.save(counters);
        bundleMetadataRepository.save(new BundleMetadata(encryptedBundleId, bundleCounter, clientId, ackCounter));
    }

    /**
     * Check the last sent bundle against the current outgoing data to see if a new bundle is needed.
     */
    public boolean newDataToSend(String lastBundleSent) {
        var bundleMetadata = bundleMetadataRepository.findById(lastBundleSent);
        if (bundleMetadata.isEmpty()) {
            return true;
        }
        var clientId = bundleMetadata.get().clientId;
        var details = sentAduDetailsRepository.findByBundleId(lastBundleSent);
        var lastAdus = new HashMap<String, Long>();
        details.forEach(d -> lastAdus.put(d.appId, d.aduIdRangeEnd));
        return registeredAppAdapterRepository.findAllAppIds().stream().anyMatch(app -> {
            var lastStoredAdu = sendADUsStorage.getLastADUIdAdded(clientId, app);
            var lastDeletedAdu = sendADUsStorage.getLastADUIdDeleted(clientId, app);
            return lastStoredAdu > Math.max(lastAdus.getOrDefault(app, 0L), lastDeletedAdu);
        });
    }

    public boolean newAckNeeded(String lastSentBundleId) {
        var bundleDetails = bundleMetadataRepository.findById(lastSentBundleId);
        if (bundleDetails.isEmpty()) {
            return false;
        }
        var counters = getBundleCountersForClient(bundleDetails.get().clientId);
        return bundleDetails.get().ackCounter < counters.lastReceivedBundleCounter;
    }

    public interface AduDeliveredListener {
        void onAduDelivered(String clientId, Set<String> appId);
    }
}
