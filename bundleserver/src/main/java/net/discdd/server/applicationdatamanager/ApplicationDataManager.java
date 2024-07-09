package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
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

    @Autowired
    private StateManager stateManager;

    private DataStoreAdaptor dataStoreAdaptor;

    @Autowired
    private BundleServerConfig bundleServerConfig;

    @Value("${bundle-server.bundle-store-root}")
    private String rootDataDir;

    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;

    public ApplicationDataManager(RegisteredAppAdapterRepository registeredAppAdapterRepository) {
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
        this.stateManager.processAcknowledgement(clientId, bundleId);
    }

    public void storeADUs(String clientId, String bundleId, List<ADU> adus) throws IOException {
        logger.log(INFO, "[ApplicationDataManager] Store ADUs");
        this.registerRecvdBundleId(clientId, bundleId);
        Map<String, List<ADU>> appIdToADUMap = new HashMap<>();

        List<String> registeredAppIds = this.getRegisteredAppIds();
        for (String registeredAppId : registeredAppIds) {
            appIdToADUMap.put(registeredAppId, new ArrayList<>());
        }
        for (ADU adu : adus) {
            logger.log(INFO, "[ApplicationDataManager] " + adu.getADUId());
            Long largestAduIdReceived = this.stateManager.largestADUIdReceived(clientId, adu.getAppId());
            if (largestAduIdReceived != null && adu.getADUId() <= largestAduIdReceived) {
                continue;
            }
            this.stateManager.updateLargestADUIdReceived(clientId, adu.getAppId(), adu.getADUId());

            if (appIdToADUMap.containsKey(adu.getAppId())) {
                appIdToADUMap.get(adu.getAppId()).add(adu);
            } else {
                appIdToADUMap.put(adu.getAppId(), new ArrayList<>());
                appIdToADUMap.get(adu.getAppId()).add(adu);
            }
        }
        for (String appId : appIdToADUMap.keySet()) {
            logger.log(INFO, "[ApplicationDataManager] " + appId + " " + appIdToADUMap.get(appId));
            this.dataStoreAdaptor.persistADUsForServer(clientId, appId, appIdToADUMap.get(appId));
        }
    }

    public List<ADU> fetchADUs(long initialSize, String clientId) {
        List<ADU> res = new ArrayList<>();
        for (String appId : this.getRegisteredAppIds()) {
            Long largestAduIdDelivered = this.stateManager.getLargestADUIdDeliveredByAppId(clientId, appId);
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
        this.stateManager.registerSentBundleDetails(clientId, bundle);
    }

    public Optional<UncompressedPayload.Builder> getLastSentBundlePayloadBuilder(String clientId) {
        return this.stateManager.getLastSentBundlePayloadBuilder(clientId);
    }

    private void registerRecvdBundleId(String clientId, String bundleId) {
        this.stateManager.registerRecvdBundleId(clientId, bundleId);
    }

    public Optional<String> getLargestRecvdBundleId(String clientId) {
        return this.stateManager.getLargestRecvdBundleId(clientId);
    }

    public String getClientIdFromSentBundleId(String bundleId) {
        return this.stateManager.getClientIdFromSentBundleId(bundleId);
    }

}
