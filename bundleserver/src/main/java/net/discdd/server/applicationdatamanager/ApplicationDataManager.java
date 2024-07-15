package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.LargestAduIdDeliveredRepository;
import net.discdd.server.repository.LargestAduIdReceivedRepository;
import net.discdd.server.repository.LargestBundleIdReceivedRepository;
import net.discdd.server.repository.LastBundleIdSentRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.SentBundleDetailsRepository;
import net.discdd.server.repository.entity.LargestAduIdDelivered;
import net.discdd.server.repository.entity.LargestAduIdReceived;
import net.discdd.server.repository.entity.LargestBundleIdReceived;
import net.discdd.server.repository.entity.LastBundleIdSent;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.server.repository.entity.SentAduDetails;
import net.discdd.server.repository.entity.SentBundleDetails;
import net.discdd.utils.BundleUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@Service
public class ApplicationDataManager {

    private static final Logger logger = Logger.getLogger(ApplicationDataManager.class.getName());

    private DataStoreAdaptor dataStoreAdaptor;

    private final BundleServerConfig bundleServerConfig;

    @Value("${bundle-server.bundle-store-root}")
    private String rootDataDir;

    private final LargestAduIdReceivedRepository largestAduIdReceivedRepository;

    private final LargestAduIdDeliveredRepository largestAduIdDeliveredRepository;

    private final LastBundleIdSentRepository lastBundleIdSentRepository;

    private final LargestBundleIdReceivedRepository largestBundleIdReceivedRepository;

    private final SentBundleDetailsRepository sentBundleDetailsRepository;

    private final SentAduDetailsRepository sentAduDetailsRepository;

    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;

    public ApplicationDataManager(LargestAduIdReceivedRepository largestAduIdReceivedRepository,
                                  LargestAduIdDeliveredRepository largestAduIdDeliveredRepository,
                                  LastBundleIdSentRepository lastBundleIdSentRepository,
                                  LargestBundleIdReceivedRepository largestBundleIdReceivedRepository,
                                  SentBundleDetailsRepository sentBundleDetailsRepository,
                                  SentAduDetailsRepository sentAduDetailsRepository,
                                  RegisteredAppAdapterRepository registeredAppAdapterRepository,
                                  BundleServerConfig bundleServerConfig) {
        this.largestAduIdReceivedRepository = largestAduIdReceivedRepository;
        this.largestAduIdDeliveredRepository = largestAduIdDeliveredRepository;
        this.lastBundleIdSentRepository = lastBundleIdSentRepository;
        this.largestBundleIdReceivedRepository = largestBundleIdReceivedRepository;
        this.sentBundleDetailsRepository = sentBundleDetailsRepository;
        this.sentAduDetailsRepository = sentAduDetailsRepository;
        this.bundleServerConfig = bundleServerConfig;
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
    }

    @PostConstruct
    private void init() {
        this.dataStoreAdaptor = new DataStoreAdaptor(rootDataDir);
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
            String appId = sentAduDetails.getAppId();
            Long lastAduIdForAppId = sentAduDetails.getAduIdRangeEnd();
            dataStoreAdaptor.deleteADUs(clientId, appId, lastAduIdForAppId);
            Optional<LargestAduIdDelivered> opt =
                    largestAduIdDeliveredRepository.findByClientIdAndAppId(clientId, appId);
            if (opt.isPresent()) {
                var record = opt.get();
                if (record.getAduId() < lastAduIdForAppId) {
                    record.setAduId(lastAduIdForAppId);
                    largestAduIdDeliveredRepository.save(record);
                }
            } else {
                largestAduIdDeliveredRepository.save(new LargestAduIdDelivered(clientId, appId, lastAduIdForAppId));
            }
        }

        sentAduDetailsRepository.deleteByBundleId(bundleId);

        logger.log(INFO, "[StateManager] Processed acknowledgement for sent bundle id " + bundleId +
                " corresponding to client " + clientId);
    }

    public void storeADUs(String clientId, String bundleId, List<ADU> adus) throws IOException {
        logger.log(INFO, "[ApplicationDataManager] Store ADUs");

        LargestBundleIdReceived largestBundleIdReceived = new LargestBundleIdReceived(clientId, bundleId);
        this.largestBundleIdReceivedRepository.save(largestBundleIdReceived);

        logger.log(INFO, "[StateManager]] Registered bundle identifier: " + bundleId + " of client " + clientId);
        Map<String, List<ADU>> appIdToADUMap = new HashMap<>();
        for (var adu : adus) {
            appIdToADUMap.computeIfAbsent(adu.getAppId(), k -> new ArrayList<>()).add(adu);
        }
        for (String appId : appIdToADUMap.keySet()) {
            List<ADU> aduList = appIdToADUMap.get(appId);
            long largestAduIdReceived = largestAduIdReceivedRepository.findByClientIdAndAppId(clientId, appId).map(LargestAduIdReceived::getAduId).orElse(-1L);
            long largestAduSeen = -1;
            for (var it = aduList.iterator(); it.hasNext(); ) {
                ADU adu = it.next();
                if (adu.getADUId() <= largestAduIdReceived) it.remove();
                if (adu.getADUId() > largestAduSeen) largestAduSeen = adu.getADUId();
            }
            this.dataStoreAdaptor.persistADUsForServer(clientId, appId, aduList);
            if (largestAduSeen > largestAduIdReceived) {
                largestAduIdReceivedRepository.save(new LargestAduIdReceived(clientId, appId, largestAduSeen));
            }
        }
    }

    public List<ADU> fetchADUs(long initialSize, String clientId) {
        List<ADU> res = new ArrayList<>();
        for (String appId : this.getRegisteredAppIds()) {
            Long ret = null;
            Optional<LargestAduIdDelivered> opt = largestAduIdDeliveredRepository.findByClientIdAndAppId(clientId, appId);
            if (opt.isPresent()) {
                LargestAduIdDelivered record = opt.get();
                ret = record.getAduId();
            }
            Long largestAduIdDelivered = ret;
            Long aduIdStart = (largestAduIdDelivered != null) ? (largestAduIdDelivered + 1) : 1;
            List<ADU> adus = this.dataStoreAdaptor.fetchADUs(clientId, appId, aduIdStart);
            long cumulativeSize = initialSize;
            for (ADU adu : adus) {
                if (adu.getSize() + cumulativeSize >
                        this.bundleServerConfig.getApplicationDataManager().getAppDataSizeLimit()) {
                    break;
                }
                res.add(adu);
                cumulativeSize += adu.getSize();
            }
        }
        return res;
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
            SentAduDetails sentAduDetails =
                    new SentAduDetails(bundle.getBundleId(), appId, minmax[0], minmax[1]);
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
                    ADU adu = dataStoreAdaptor.fetchADU(clientId, appId, aduId);
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
        return largestBundleIdReceivedRepository.findByClientId(clientId).map(LargestBundleIdReceived::getBundleId).orElse(null);
    }

    public String getClientIdFromSentBundleId(String bundleId) {
        return sentBundleDetailsRepository.findByBundleId(bundleId).map(SentBundleDetails::getClientId).orElse(null);
    }
}
