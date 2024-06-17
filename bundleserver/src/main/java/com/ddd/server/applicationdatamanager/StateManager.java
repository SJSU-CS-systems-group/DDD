package com.ddd.server.applicationdatamanager;

import com.ddd.model.ADU;
import com.ddd.model.UncompressedPayload;
import com.ddd.server.bundletransmission.BundleGeneratorService;
import com.ddd.server.repository.LargestAduIdDeliveredRepository;
import com.ddd.server.repository.LargestAduIdReceivedRepository;
import com.ddd.server.repository.LargestBundleIdReceivedRepository;
import com.ddd.server.repository.LastBundleIdSentRepository;
import com.ddd.server.repository.SentAduDetailsRepository;
import com.ddd.server.repository.SentBundleDetailsRepository;
import com.ddd.server.repository.entity.LargestAduIdDelivered;
import com.ddd.server.repository.entity.LargestAduIdReceived;
import com.ddd.server.repository.entity.LargestBundleIdReceived;
import com.ddd.server.repository.entity.LastBundleIdSent;
import com.ddd.server.repository.entity.SentAduDetails;
import com.ddd.server.repository.entity.SentBundleDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Service
class StateManager {
    private static final Logger logger = Logger.getLogger(StateManager.class.getName());

    private DataStoreAdaptor dataStoreAdaptor;

    private LargestAduIdReceivedRepository largestAduIdReceivedRepository;

    private LargestAduIdDeliveredRepository largestAduIdDeliveredRepository;

    private LastBundleIdSentRepository lastBundleIdSentRepository;

    private LargestBundleIdReceivedRepository largestBundleIdReceivedRepository;

    private SentBundleDetailsRepository sentBundleDetailsRepository;

    private SentAduDetailsRepository sentAduDetailsRepository;

    public StateManager(LargestAduIdReceivedRepository largestAduIdReceivedRepository,
                        LargestAduIdDeliveredRepository largestAduIdDeliveredRepository,
                        LastBundleIdSentRepository lastBundleIdSentRepository,
                        SentBundleDetailsRepository sentBundleDetailsRepository,
                        SentAduDetailsRepository sentAduDetailsRepository,
                        LargestBundleIdReceivedRepository largestBundleIdReceivedRepository) {
        this.largestAduIdReceivedRepository = largestAduIdReceivedRepository;
        this.largestAduIdDeliveredRepository = largestAduIdDeliveredRepository;
        this.lastBundleIdSentRepository = lastBundleIdSentRepository;
        this.sentBundleDetailsRepository = sentBundleDetailsRepository;
        this.sentAduDetailsRepository = sentAduDetailsRepository;
        this.largestBundleIdReceivedRepository = largestBundleIdReceivedRepository;
    }

    @Value("${bundle-server.bundle-store-root}")
    public void setDataStoreAdaptor(String rootDataDir) {
        this.dataStoreAdaptor = new DataStoreAdaptor(rootDataDir);
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerRecvdBundleId(String clientId, String bundleId) {
        LargestBundleIdReceived largestBundleIdReceived = new LargestBundleIdReceived(clientId, bundleId);
        this.largestBundleIdReceivedRepository.save(largestBundleIdReceived);
        logger.log(INFO, "[StateManager]] Registered bundle identifier: " + bundleId + " of client " + clientId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long largestADUIdReceived(String clientId, String appId) {
        Long ret = null;
        Optional<LargestAduIdReceived> opt =
                this.largestAduIdReceivedRepository.findByClientIdAndAppId(clientId, appId);
        if (opt.isPresent()) {
            LargestAduIdReceived record = opt.get();
            ret = record.getAduId();
        }
        return ret;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateLargestADUIdReceived(String clientId, String appId, Long aduId) {
        Optional<LargestAduIdReceived> opt =
                this.largestAduIdReceivedRepository.findByClientIdAndAppId(clientId, appId);
        LargestAduIdReceived record = null;
        if (opt.isPresent()) {
            record = opt.get();
            record.setAduId(aduId);
        } else {
            record = new LargestAduIdReceived(clientId, appId, aduId);
        }
        this.largestAduIdReceivedRepository.save(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long getLargestADUIdDeliveredByAppId(String clientId, String appId) {
        Long ret = null;
        Optional<LargestAduIdDelivered> opt =
                this.largestAduIdDeliveredRepository.findByClientIdAndAppId(clientId, appId);
        if (opt.isPresent()) {
            LargestAduIdDelivered record = opt.get();
            ret = record.getAduId();
        }
        return ret;
    }

    /* Sent bundle Details*/
    private Map<String, Long> getSentBundleAduRangeDetails(String bundleId) {
        Map<String, Long> ret = new HashMap<>();
        List<SentAduDetails> sentAduDetailsList = this.sentAduDetailsRepository.findByBundleId(bundleId);

        for (SentAduDetails sentAduDetails : sentAduDetailsList) {
            ret.put(sentAduDetails.getAppId(), sentAduDetails.getAduIdRangeEnd());
        }

        return ret;
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerSentBundleDetails(String clientId, UncompressedPayload sentBundle) {
        LastBundleIdSent lastBundleIdSent = new LastBundleIdSent(clientId, sentBundle.getBundleId());
        this.lastBundleIdSentRepository.save(lastBundleIdSent);
        this.writeLastSentBundleStructure(clientId, sentBundle);
    }

    private Map<String, Object> getLastSentBundleStructure(String clientId) {
        Map<String, Object> ret = new HashMap<>();
        Optional<LastBundleIdSent> opt = this.lastBundleIdSentRepository.findByClientId(clientId);
        if (opt.isPresent()) {
            String bundleId = opt.get().getBundleId();
            Optional<SentBundleDetails> bundleDetailsOpt = this.sentBundleDetailsRepository.findByBundleId(bundleId);

            SentBundleDetails bundleDetails =
                    bundleDetailsOpt.get(); // assume this is guaranteed to be present. TODO: add FK on LastBundleIdSent
            // to SentBundleDetails
            ret.put("bundle-id", bundleId);
            ret.put("acknowledgement", bundleDetails.getAckedBundleId());

            List<SentAduDetails> bundleAduDetailsList = this.sentAduDetailsRepository.findByBundleId(bundleId);

            //      Map<String, List<Object>> aduRangeMap = new HashMap<>();
            //      for (SentAduDetails bundleAduDetails : bundleAduDetailsList) {
            //        aduRangeMap.put(
            //            bundleAduDetails.getAppId(),
            //            Arrays.asList(
            //                new Object[] {
            //                  bundleAduDetails.getAduIdRangeStart(), bundleAduDetails.getAduIdRangeEnd()
            //                }));
            //      }

            Map<String, List<ADU>> aduMap = new HashMap<>();
            for (SentAduDetails bundleAduDetails : bundleAduDetailsList) {
                Long rangeStart = bundleAduDetails.getAduIdRangeStart();
                Long rangeEnd = bundleAduDetails.getAduIdRangeEnd();
                String appId = bundleAduDetails.getAppId();
                List<ADU> aduList = new ArrayList<>();
                for (Long aduId = rangeStart; aduId <= rangeEnd; aduId++) {
                    ADU adu = this.dataStoreAdaptor.fetchADU(clientId, appId, aduId);
                    aduList.add(adu);
                }
                aduMap.put(appId, aduList);
            }
            if (!aduMap.isEmpty()) {
                ret.put("ADU", aduMap);
            }
            //      if (!aduRangeMap.isEmpty()) {
            //        ret.put("ADU", aduRangeMap);
            //      }
        }
        return ret;
    }

    private void writeLastSentBundleStructure(String clientId, UncompressedPayload lastSentBundle) {
        Optional<SentBundleDetails> opt = this.sentBundleDetailsRepository.findByBundleId(lastSentBundle.getBundleId());
        if (!opt.isEmpty()) {
            return;
        }
        SentBundleDetails sentBundleDetails = new SentBundleDetails();
        sentBundleDetails.setAckedBundleId(lastSentBundle.getAckRecord().getBundleId());
        sentBundleDetails.setClientId(clientId);
        sentBundleDetails.setBundleId(lastSentBundle.getBundleId());
        this.sentBundleDetailsRepository.save(sentBundleDetails);

        Map<String, Long[]> aduRangeMap = new HashMap<>();
        for (ADU adu : lastSentBundle.getADUs()) {
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
            SentAduDetails sentAduDetails =
                    new SentAduDetails(lastSentBundle.getBundleId(), appId, minmax[0], minmax[1]);
            this.sentAduDetailsRepository.save(sentAduDetails);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void processAcknowledgement(String clientId, String bundleId) {
        Map<String, Long> sentDetails = this.getSentBundleAduRangeDetails(bundleId);
        for (String appId : sentDetails.keySet()) {
            this.dataStoreAdaptor.deleteADUs(clientId, appId, sentDetails.get(appId));
            Optional<LargestAduIdDelivered> opt =
                    this.largestAduIdDeliveredRepository.findByClientIdAndAppId(clientId, appId);
            LargestAduIdDelivered record = null;
            Long deliveredAduId = sentDetails.get(appId);
            if (opt.isPresent()) {
                record = opt.get();
                if (record.getAduId() < deliveredAduId) {
                    record.setAduId(deliveredAduId);
                }
            } else {
                record = new LargestAduIdDelivered(clientId, appId, deliveredAduId);
            }
            this.largestAduIdDeliveredRepository.save(record);
        }

        logger.log(INFO, "[StateManager] Processed acknowledgement for sent bundle id " + bundleId +
                " corresponding to client " + clientId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<UncompressedPayload.Builder> getLastSentBundlePayloadBuilder(String clientId) {
        Map<String, Object> structure = this.getLastSentBundleStructure(clientId);
        return BundleGeneratorService.bundleStructureToBuilder(structure);
    }

    public Optional<String> getLargestRecvdBundleId(String clientId) {
        Optional<LargestBundleIdReceived> opt = this.largestBundleIdReceivedRepository.findByClientId(clientId);
        return (opt.isPresent() ? Optional.of(opt.get().getBundleId()) : Optional.empty());
    }

    public String getClientIdFromSentBundleId(String bundleId) {
        Optional<SentBundleDetails> opt = sentBundleDetailsRepository.findByBundleId(bundleId);
        return opt.get().getClientId(); // TODO handle optional empty case
    }
}
