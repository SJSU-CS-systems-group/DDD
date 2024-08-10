package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.LargestBundleIdReceivedRepository;
import net.discdd.server.repository.LastBundleIdSentRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.SentBundleDetailsRepository;
import net.discdd.server.repository.entity.LargestBundleIdReceived;
import net.discdd.server.repository.entity.LastBundleIdSent;
import net.discdd.server.repository.entity.SentAduDetails;
import net.discdd.server.repository.entity.SentBundleDetails;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.StoreADUs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Service
public class ApplicationDataManager {
    private static final Logger logger = Logger.getLogger(ApplicationDataManager.class.getName());
    private final BundleServerConfig bundleServerConfig;
    private final LastBundleIdSentRepository lastBundleIdSentRepository;
    private final LargestBundleIdReceivedRepository largestBundleIdReceivedRepository;
    private final SentBundleDetailsRepository sentBundleDetailsRepository;
    private final SentAduDetailsRepository sentAduDetailsRepository;
    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;
    AduDeliveredListener aduDeliveredListener;
    private final StoreADUs receiveADUsStorage;
    private final StoreADUs sendADUsStorage;

    public ApplicationDataManager(AduStores aduStores, AduDeliveredListener aduDeliveredListener,
                                  LastBundleIdSentRepository lastBundleIdSentRepository,
                                  LargestBundleIdReceivedRepository largestBundleIdReceivedRepository,
                                  SentBundleDetailsRepository sentBundleDetailsRepository,
                                  SentAduDetailsRepository sentAduDetailsRepository,
                                  RegisteredAppAdapterRepository registeredAppAdapterRepository,
                                  BundleServerConfig bundleServerConfig) {
        this.aduDeliveredListener = aduDeliveredListener;
        this.lastBundleIdSentRepository = lastBundleIdSentRepository;
        this.largestBundleIdReceivedRepository = largestBundleIdReceivedRepository;
        this.sentBundleDetailsRepository = sentBundleDetailsRepository;
        this.sentAduDetailsRepository = sentAduDetailsRepository;
        this.bundleServerConfig = bundleServerConfig;
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        this.sendADUsStorage = aduStores.getSendADUsStorage();
        this.receiveADUsStorage = aduStores.getReceiveADUsStorage();
    }

    public List<String> getRegisteredAppIds() {
        return registeredAppAdapterRepository.findAllAppIds().stream().toList();
    }

    @Transactional
    public void processAcknowledgement(String clientId, String bundleId) throws IOException {
        if ("HB".equals(bundleId)) {
            return;
        }

        List<SentAduDetails> sentAduDetailsList = this.sentAduDetailsRepository.findByBundleId(bundleId);

        for (SentAduDetails sentAduDetails : sentAduDetailsList) {
            String appId = sentAduDetails.getAppId();
            Long lastAduIdForAppId = sentAduDetails.getAduIdRangeEnd();
            sendADUsStorage.deleteAllFilesUpTo(clientId, appId, lastAduIdForAppId);
            logger.log(INFO, "[DataStoreAdaptor] Deleted ADUs for application " + appId + " with id upto " +
                    lastAduIdForAppId);
        }

        sentAduDetailsRepository.deleteByBundleId(bundleId);

        logger.log(INFO, "[StateManager] Processed acknowledgement for sent bundle id " + bundleId +
                " corresponding to client " + clientId);
    }

    public void storeReceivedADUs(String clientId, String bundleId, List<ADU> adus) throws IOException {
        logger.log(INFO, "[ApplicationDataManager] Store ADUs");

        LargestBundleIdReceived largestBundleIdReceived = new LargestBundleIdReceived(clientId, bundleId);
        try {
            this.largestBundleIdReceivedRepository.save(largestBundleIdReceived);
        } catch (Exception e) {
            logger.log(INFO, "[StateManager] Failed to store largest bundle id received for client " + clientId, e);
            return;
        }
        logger.log(INFO, "[StateManager] Registered bundle identifier: " + bundleId + " of client " + clientId);
        var affectedAppIds = new HashSet<String>();

        Map<String, List<ADU>> appIdToADUMap = new HashMap<>();
        for (var adu : adus) {
            var addedFile =
                    receiveADUsStorage.addADU(clientId, adu.getAppId(), Files.readAllBytes(adu.getSource().toPath()),
                                              adu.getADUId());
            if (addedFile != null) affectedAppIds.add(adu.getAppId());
        }
        aduDeliveredListener.onAduDelivered(clientId, affectedAppIds);
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

    public List<ADU> fetchADUsToSend(long initialSize, String clientId) throws IOException {
        List<ADU> adusToSend = new ArrayList<>();
        final long dataSizeLimit = this.bundleServerConfig.getApplicationDataManager().getAppDataSizeLimit();
        var sizeLimiter = new SizeLimiter(dataSizeLimit - initialSize);
        for (String appId : this.getRegisteredAppIds()) {
            sendADUsStorage.getADUs(clientId, appId).takeWhile(a -> sizeLimiter.test(a.getSize()))
                    .forEach(adusToSend::add);
        }
        return adusToSend;
    }

    public ADU fetchSentADU(String clientId, String appId, long aduId) {
        File file = sendADUsStorage.getADUFile(clientId, appId, aduId);
        return file.isFile() ? new ADU(file, appId, aduId, file.length(), clientId) : null;
    }

    public void notifyBundleGenerated(String clientId, UncompressedPayload bundle) {
        LastBundleIdSent lastBundleIdSent = new LastBundleIdSent(clientId, bundle.getBundleId());
        lastBundleIdSentRepository.save(lastBundleIdSent);
        Optional<SentBundleDetails> opt = sentBundleDetailsRepository.findByBundleId(bundle.getBundleId());
        if (opt.isPresent()) {
            return;
        }
        SentBundleDetails sentBundleDetails = new SentBundleDetails();
        sentBundleDetails.setAckedBundleId(bundle.getAckRecord().getBundleId());
        sentBundleDetails.setClientId(clientId);
        sentBundleDetails.setBundleId(bundle.getBundleId());
        sentBundleDetailsRepository.save(sentBundleDetails);

        Map<String, Long[]> aduRangeMap = new HashMap<>();
        for (ADU adu : bundle.getADUs()) {
            Long[] entry = null;
            if (aduRangeMap.containsKey(adu.getAppId())) {
                Long[] minmax = aduRangeMap.get(adu.getAppId());
                minmax[0] = Math.min(minmax[0], adu.getADUId());
                minmax[1] = Math.max(minmax[1], adu.getADUId());
                entry = minmax;
            } else {
                entry = new Long[] { adu.getADUId(), adu.getADUId() };
            }
            aduRangeMap.put(adu.getAppId(), entry);
        }

        for (String appId : aduRangeMap.keySet()) {
            Long[] minmax = aduRangeMap.get(appId);
            SentAduDetails sentAduDetails = new SentAduDetails(bundle.getBundleId(), appId, minmax[0], minmax[1]);
            sentAduDetailsRepository.save(sentAduDetails);
        }
    }

    public Optional<UncompressedPayload.Builder> getLastSentBundlePayloadBuilder(String clientId) {
        Map<String, Object> ret = new HashMap<>();
        Optional<LastBundleIdSent> opt = lastBundleIdSentRepository.findByClientId(clientId);
        if (opt.isPresent()) {
            String bundleId = opt.get().getBundleId();
            Optional<SentBundleDetails> bundleDetailsOpt = sentBundleDetailsRepository.findByBundleId(bundleId);

            SentBundleDetails bundleDetails =
                    bundleDetailsOpt.get(); // assume this is guaranteed to be present. TODO: add FK on LastBundleIdSent
            // to SentBundleDetails
            ret.put("bundle-id", bundleId);
            ret.put("acknowledgement", bundleDetails.getAckedBundleId());

            List<SentAduDetails> bundleAduDetailsList = sentAduDetailsRepository.findByBundleId(bundleId);

            Map<String, List<ADU>> aduMap = new HashMap<>();
            for (SentAduDetails bundleAduDetails : bundleAduDetailsList) {
                Long rangeStart = bundleAduDetails.getAduIdRangeStart();
                Long rangeEnd = bundleAduDetails.getAduIdRangeEnd();
                String appId = bundleAduDetails.getAppId();
                List<ADU> aduList = new ArrayList<>();
                for (Long aduId = rangeStart; aduId <= rangeEnd; aduId++) {
                    ADU adu = fetchSentADU(clientId, appId, aduId);
                    aduList.add(adu);
                }
                aduMap.put(appId, aduList);
            }
            if (!aduMap.isEmpty()) {
                ret.put("ADU", aduMap);
            }
        }
        return BundleUtils.bundleStructureToBuilder(ret);
    }

    public String getLargestRecvdBundleId(String clientId) {
        return largestBundleIdReceivedRepository.findByClientId(clientId).map(LargestBundleIdReceived::getBundleId)
                .orElse(null);
    }

    public String getClientIdFromSentBundleId(String bundleId) {
        return sentBundleDetailsRepository.findByBundleId(bundleId).map(SentBundleDetails::getClientId).orElse(null);
    }

    public interface AduDeliveredListener {
        void onAduDelivered(String clientId, Set<String> appId);
    }
}
