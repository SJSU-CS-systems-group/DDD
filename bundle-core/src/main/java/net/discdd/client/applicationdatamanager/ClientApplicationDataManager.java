package net.discdd.client.applicationdatamanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.StoreADUs;
import net.discdd.utils.StreamExt;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ClientApplicationDataManager {

    private static final Logger logger = Logger.getLogger(ClientApplicationDataManager.class.getName());

    public final StoreADUs sendADUsStorage;
    public final StoreADUs receiveADUsStorage;
    final private Consumer<ADU> aduConsumer;
    /* Database tables */

    final private ClientPaths clientPaths;

    public ClientApplicationDataManager(ClientPaths clientPaths, Consumer<ADU> aduConsumer) {
        this.clientPaths = clientPaths;
        sendADUsStorage = new StoreADUs(clientPaths.sendADUsPath);
        receiveADUsStorage = new StoreADUs(clientPaths.receiveADUsPath);
        this.aduConsumer = aduConsumer;

        try {
            File sentBundleDetails = clientPaths.sendBundleDetailsPath.toFile();
            sentBundleDetails.createNewFile();
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to create send bundle details file", e);
        }
    }

    // we cannot use .toList() since we are targeting Java 11, but Intellij really wants us to
    @SuppressWarnings("SimplifyStreamApiCallChains")
    public List<String> getRegisteredAppIds() {
        return sendADUsStorage.getAllClientApps(true)
                .map(StoreADUs.ClientApp::appId)
                .collect(Collectors.toUnmodifiableList());
    }

    public void processAcknowledgement(String bundleId) {
        logger.log(FINE, "[ADM] Processing acknowledgement for sent bundle id: " + bundleId);
        if ("HB".equals(bundleId)) {
            logger.log(INFO, "[ADM] This is a heartbeat message.");
            return;
        }

        System.out.println("[ADM-SM] Registering acknowledgement for sent bundle id: " + bundleId);
        Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
        Map<String, Long> details = sentBundleDetails.getOrDefault(bundleId, new HashMap<>());
        for (String appId : details.keySet()) {
            Long aduId = details.get(appId);
            try {
                sendADUsStorage.deleteAllFilesUpTo(null, appId, aduId);
            } catch (IOException e) {
                logger.log(WARNING, "Could not delete ADUs up to adu: " + aduId, e);
            }
        }
    }

    public void storeReceivedADUs(String clientId, String bundleId, List<ADU> adus) {
        logger.log(INFO, "[ADM] Storing ADUs in the Data Store, Size:" + adus.size());
        for (final ADU adu : adus) {
            try {
                var aduFile = receiveADUsStorage.addADU(null,
                                                        adu.getAppId(),
                                                        Files.readAllBytes(adu.getSource().toPath()),
                                                        adu.getADUId());
                // if aduFile == null, the ADU was not added
                if (aduFile != null) {
                    aduConsumer.accept(adu);
                }
                logger.log(FINE, "[ADM] Updated Largest ADU id: " + adu.getADUId() + "," + adu.getSource());
            } catch (IOException e) {
                logger.log(WARNING, "Could not persist adu: " + adu.getADUId(), e);
            }
        }
        // this indicates that the batch of ADUs has been finished
        aduConsumer.accept(null);
    }

    public List<ADU> fetchADUsToSend(long initialSize, String clientId) throws IOException {
        List<ADU> adusToSend = new ArrayList<>();
        final long dataSizeLimit = ClientPaths.APP_DATA_SIZE_LIMIT;
        var sizeLimiter = new SizeLimiter(dataSizeLimit - initialSize);
        for (String appId : this.getRegisteredAppIds()) {
            StreamExt.takeWhile(sendADUsStorage.getADUs(clientId, appId), a -> sizeLimiter.test(a.getSize()))
                    .forEach(adusToSend::add);
        }
        return adusToSend;
    }

    public void notifyBundleSent(List<ADU> sentAdus, String bundleId) {
        if (sentAdus.isEmpty()) {
            return;
        }
        Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
        if (sentBundleDetails.containsKey(bundleId)) {
            return;
        }
        Map<String, Long> bundleDetails = new HashMap<>();
        for (ADU adu : sentAdus) {
            String appId = adu.getAppId();
            Long aduId = adu.getADUId();

            if (!bundleDetails.containsKey(appId) || bundleDetails.get(appId) < aduId) {
                bundleDetails.put(appId, aduId);
            }
        }
        sentBundleDetails.put(bundleId, bundleDetails);
        this.writeSentBundleDetails(sentBundleDetails);
    }

    private void writeSentBundleDetails(Map<String, Map<String, Long>> sentBundleDetails) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(sentBundleDetails);
        try (FileWriter writer = new FileWriter(clientPaths.sendBundleDetailsPath.toFile())) {
            writer.write(jsonString);
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to write sent bundle details", e);
        }
    }

    public boolean hasNewADUs(String clientId, long lastBundleSentTimestamp) {
        return sendADUsStorage.hasNewADUs(clientId, lastBundleSentTimestamp);
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

    private Map<String, Map<String, Long>> getSentBundleDetails() {
        Gson gson = new Gson();
        Map<String, Map<String, Long>> ret = new HashMap<>();
        try (FileReader reader = new FileReader(clientPaths.sendBundleDetailsPath.toFile())) {
            Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
            ret = gson.fromJson(reader, mapType);
            if (ret == null) {
                ret = new HashMap<>();
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to read sent bundle details", e);
        }
        return ret;
    }
}
