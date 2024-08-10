package net.discdd.client.applicationdatamanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.discdd.model.ADU;
import net.discdd.model.UncompressedPayload;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.StoreADUs;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ApplicationDataManager {

    private static final Logger logger = Logger.getLogger(ApplicationDataManager.class.getName());

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;
    private Consumer<ADU> aduConsumer;
    /* Database tables */
    private static String SENT_BUNDLE_DETAILS = "Shared/DB/SENT_BUNDLE_DETAILS.json";

    private static String LAST_SENT_BUNDLE_STRUCTURE = "Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

    private Long APP_DATA_SIZE_LIMIT = 1000000000L;

    private static List<String> REGISTER_APP_IDS = List.of("com.example.mysignal", "com.fsck.k9.debug", "testAppId");

    private final Path ROOT_DIR;

    public ApplicationDataManager(Path rootDir, Consumer<ADU> aduConsumer) {
        ROOT_DIR = rootDir;
        sendADUsStorage = new StoreADUs(rootDir.resolve("send"));
        receiveADUsStorage = new StoreADUs(rootDir.resolve("receive"));
        this.aduConsumer = aduConsumer;

        try {
            File sentBundleDetails = ROOT_DIR.resolve(SENT_BUNDLE_DETAILS).toFile();
            sentBundleDetails.createNewFile();

            File lastSentBundleStructure = ROOT_DIR.resolve(LAST_SENT_BUNDLE_STRUCTURE).toFile();
            lastSentBundleStructure.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<String> getRegisteredAppIds() throws IOException {
        return REGISTER_APP_IDS;
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
                continue;
            }
        }
    }

    public void storeReceivedADUs(String clientId, String bundleId, List<ADU> adus) {
        System.out.println("[ADM] Storing ADUs in the Data Store, Size:" + adus.size());

        for (final ADU adu : adus) {

            try {
                receiveADUsStorage.addADU(null, adu.getAppId(), Files.readAllBytes(adu.getSource().toPath()),
                                          adu.getADUId());
                aduConsumer.accept(adu);
                logger.log(FINE, "[ADM] Updated Largest ADU id: " + adu.getADUId() + "," + adu.getSource());
            } catch (IOException e) {
                logger.log(WARNING, "Could not persist adu: " + adu.getADUId(), e);
            }
        }
    }

    public List<ADU> fetchADUsToSend(long initialSize, String clientId) throws IOException {
        List<ADU> adusToSend = new ArrayList<>();
        final long dataSizeLimit = this.APP_DATA_SIZE_LIMIT;
        var sizeLimiter = new SizeLimiter(dataSizeLimit - initialSize);
        for (String appId : this.getRegisteredAppIds()) {
            sendADUsStorage.getADUs(clientId, appId).takeWhile(a -> sizeLimiter.test(a.getSize()))
                    .forEach(adusToSend::add);
        }
        return adusToSend;
    }

    public void notifyBundleSent(UncompressedPayload bundle) {
        if (bundle.getADUs().isEmpty()) {
            return;
        }
        Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
        if (sentBundleDetails.containsKey(bundle.getBundleId())) {
            return;
        }
        Map<String, Long> bundleDetails = new HashMap<>();
        for (ADU adu : bundle.getADUs()) {
            String appId = adu.getAppId();
            Long aduId = adu.getADUId();

            if (!bundleDetails.containsKey(appId) || bundleDetails.get(appId) < aduId) {
                bundleDetails.put(appId, aduId);
            }
        }
        sentBundleDetails.put(bundle.getBundleId(), bundleDetails);
        this.writeSentBundleDetails(sentBundleDetails);
        this.writeLastSentBundleStructure(bundle);
    }

    public Optional<UncompressedPayload.Builder> getLastSentBundleBuilder() {
        return BundleUtils.jsonToBundleBuilder(ROOT_DIR.resolve(LAST_SENT_BUNDLE_STRUCTURE).toFile());
    }

    private void writeLastSentBundleStructure(UncompressedPayload lastSentBundle) {
        BundleUtils.writeBundleStructureToJson(lastSentBundle, ROOT_DIR.resolve(LAST_SENT_BUNDLE_STRUCTURE).toFile());
    }

    private void writeSentBundleDetails(Map<String, Map<String, Long>> sentBundleDetails) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(sentBundleDetails);
        try (FileWriter writer = new FileWriter(ROOT_DIR.resolve(SENT_BUNDLE_DETAILS).toFile())) {
            writer.write(jsonString);
        } catch (IOException e) {
            e.printStackTrace();
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

    private Map<String, Map<String, Long>> getSentBundleDetails() {
        Gson gson = new Gson();
        Map<String, Map<String, Long>> ret = new HashMap<>();
        try (FileReader reader = new FileReader(ROOT_DIR.resolve(SENT_BUNDLE_DETAILS).toFile())) {
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
