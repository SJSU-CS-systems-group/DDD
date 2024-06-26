package net.discdd.client.applicationdatamanager;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import com.ddd.model.ADU;
import com.ddd.model.UncompressedPayload;
import net.discdd.utils.BundleUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

class StateManager {

    private DataStoreAdaptor dataStoreAdaptor;

    private static final Logger logger = Logger.getLogger(StateManager.class.getName());

    /* Database tables */
    private static String SENT_BUNDLE_DETAILS = "/Shared/DB/SENT_BUNDLE_DETAILS.json";

    private static String LARGEST_ADU_ID_RECEIVED = "/Shared/DB/LARGEST_ADU_ID_RECEIVED.json";

    private static String LARGEST_ADU_ID_DELIVERED = "/Shared/DB/LARGEST_ADU_ID_DELIVERED.json";

    private static String LAST_SENT_BUNDLE_STRUCTURE = "/Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

    final private Path ROOT_DIR;

    public StateManager(Path rootFolder) {
        ROOT_DIR = rootFolder;
        this.dataStoreAdaptor = new DataStoreAdaptor(rootFolder);
        try {
            File sentBundleDetails = new File(ROOT_DIR + SENT_BUNDLE_DETAILS);
            sentBundleDetails.getParentFile().mkdirs();
            sentBundleDetails.createNewFile();
            File largestADUIdReceived = new File(ROOT_DIR + LARGEST_ADU_ID_DELIVERED);
            largestADUIdReceived.getParentFile().mkdirs();
            largestADUIdReceived.createNewFile();
            File largestADUIdDelivered = new File(ROOT_DIR + LARGEST_ADU_ID_RECEIVED);
            largestADUIdDelivered.getParentFile().mkdirs();
            largestADUIdDelivered.createNewFile();
            File lastSentBundleStructure = new File(ROOT_DIR + LAST_SENT_BUNDLE_STRUCTURE);
            lastSentBundleStructure.getParentFile().mkdirs();
            lastSentBundleStructure.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Largest ADU ID received */

    private Map<String, Long> getLargestADUIdReceivedDetails() {
        Gson gson = new Gson();
        Map<String, Long> ret = null;
        try {
            Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
            ret = gson.fromJson(new FileReader(ROOT_DIR + LARGEST_ADU_ID_RECEIVED), mapType);
            if (ret == null) {
                ret = new HashMap<>();
            }
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
        } catch (JsonIOException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return ret;
    }

    private void writeLargestADUIdReceivedDetails(Map<String, Long> largestADUIdReceivedDetails) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(largestADUIdReceivedDetails);
        try (FileWriter writer = new FileWriter(new File(ROOT_DIR + LARGEST_ADU_ID_RECEIVED))) {
            writer.write(jsonString);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Long getLargestADUIdReceived(String appId) {
        Map<String, Long> receivedADUIds = this.getLargestADUIdReceivedDetails();
        return receivedADUIds.get(appId);
    }

    public void updateLargestADUIdReceived(String appId, Long aduId) {
        Map<String, Long> largestADUIdReceivedDetails = this.getLargestADUIdReceivedDetails();
        largestADUIdReceivedDetails.put(appId, aduId);
        logger.log(FINE, "[ADM] (In Progress) Updating Largest ADU id: " + aduId);
        this.writeLargestADUIdReceivedDetails(largestADUIdReceivedDetails);
    }

    /* Largest ADU ID Delivered Details*/
    private void writeLargestADUIdDeliveredDetails(Map<String, Long> largestADUIdDeliveredDetails) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(largestADUIdDeliveredDetails);
        try (FileWriter writer = new FileWriter(new File(ROOT_DIR + LARGEST_ADU_ID_DELIVERED))) {
            writer.write(jsonString);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, Long> getLargestADUIdDeliveredDetails() {
        Gson gson = new Gson();
        Map<String, Long> ret = new HashMap<>();
        try {
            Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
            ret = gson.fromJson(new FileReader(ROOT_DIR + LARGEST_ADU_ID_DELIVERED), mapType);
            if (ret == null) {
                ret = new HashMap<>();
            }
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
        } catch (JsonIOException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return ret;
    }

    public Long getLargestADUIdDeliveredByAppId(String appId) {
        return this.getLargestADUIdDeliveredDetails().get(appId);
    }

    /* Sent bundle Details*/

    private Map<String, Map<String, Long>> getSentBundleDetails() {
        Gson gson = new Gson();
        Map<String, Map<String, Long>> ret = new HashMap<>();
        try {
            Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
            ret = gson.fromJson(new FileReader(new File(ROOT_DIR + SENT_BUNDLE_DETAILS)), mapType);
            if (ret == null) {
                ret = new HashMap<>();
            }
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
        } catch (JsonIOException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return ret;
    }

    private void writeSentBundleDetails(Map<String, Map<String, Long>> sentBundleDetails) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(sentBundleDetails);
        try (FileWriter writer = new FileWriter(new File(ROOT_DIR + SENT_BUNDLE_DETAILS))) {
            writer.write(jsonString);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeLastSentBundleStructure(UncompressedPayload lastSentBundle) {
        BundleUtils.writeBundleStructureToJson(lastSentBundle, new File(ROOT_DIR + LAST_SENT_BUNDLE_STRUCTURE));
    }

    public void registerSentBundleDetails(UncompressedPayload sentBundle) {
        if (sentBundle.getADUs().isEmpty()) {
            return;
        }
        Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
        if (sentBundleDetails.containsKey(sentBundle.getBundleId())) {
            return;
        }
        Map<String, Long> bundleDetails = new HashMap<>();
        for (ADU adu : sentBundle.getADUs()) {
            String appId = adu.getAppId();
            Long aduId = adu.getADUId();

            if (!bundleDetails.containsKey(appId) || bundleDetails.get(appId) < aduId) {
                bundleDetails.put(appId, aduId);
            }
        }
        sentBundleDetails.put(sentBundle.getBundleId(), bundleDetails);
        this.writeSentBundleDetails(sentBundleDetails);
        this.writeLastSentBundleStructure(sentBundle);
    }

    public void processAcknowledgement(String bundleId) {
        System.out.println("[ADM-SM] Registering acknowledgement for sent bundle id: " + bundleId);
        Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
        Map<String, Long> details = sentBundleDetails.getOrDefault(bundleId, new HashMap<>());
        Map<String, Long> largestADUIdDeliveredDetails = this.getLargestADUIdDeliveredDetails();
        for (String appId : details.keySet()) {
            Long aduId = details.get(appId);
            try {
                this.dataStoreAdaptor.deleteADUs(appId, aduId);
            } catch (IOException e) {
                logger.log(WARNING, "Could not delete ADUs up to adu: " + aduId, e);
                continue;
            }

            if (!largestADUIdDeliveredDetails.containsKey(appId) || aduId > largestADUIdDeliveredDetails.get(appId)) {
                largestADUIdDeliveredDetails.put(appId, aduId);
            }
        }
        this.writeLargestADUIdDeliveredDetails(largestADUIdDeliveredDetails);
    }

    public Optional<UncompressedPayload.Builder> getLastSentBundleBuilder() {
        return BundleUtils.jsonToBundleBuilder(new File(ROOT_DIR + LAST_SENT_BUNDLE_STRUCTURE));
    }
}

public class ApplicationDataManager {

    private static final Logger logger = Logger.getLogger(ApplicationDataManager.class.getName());

    private StateManager stateManager;

    private DataStoreAdaptor dataStoreAdaptor;

    private Long APP_DATA_SIZE_LIMIT = 1000000000L;

    private static String REGISTERED_APP_IDS = "/Shared/REGISTERED_APP_IDS.txt";

    private final Path ROOT_DIR;

    public ApplicationDataManager(Path rootDir) {
        ROOT_DIR = rootDir;
        this.stateManager = new StateManager(rootDir);
        this.dataStoreAdaptor = new DataStoreAdaptor(rootDir);
    }

    public List<String> getRegisteredAppIds() {
        List<String> registeredAppIds = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(new File(ROOT_DIR + REGISTERED_APP_IDS)))) {
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                String appId = line.trim();
                logger.log(INFO, "Registered " + appId);
                registeredAppIds.add(appId);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return registeredAppIds;
    }

    public void registerAppId(String appId) {
        File file = ROOT_DIR.toFile();
        if (!file.exists()) {
            file.mkdirs();
        }
        logger.log(FINE, ROOT_DIR + REGISTERED_APP_IDS + "||" + appId);
        try (BufferedWriter bufferedWriter = new BufferedWriter(
                new FileWriter(new File(ROOT_DIR + REGISTERED_APP_IDS), true))) {
            bufferedWriter.append(appId + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void processAcknowledgement(String bundleId) {
        logger.log(FINE, "[ADM] Processing acknowledgement for sent bundle id: " + bundleId);
        if ("HB".equals(bundleId)) {
            logger.log(INFO, "[ADM] This is a heartbeat message.");
            return;
        }
        this.stateManager.processAcknowledgement(bundleId);
    }

    public void storeADUs(List<ADU> adus) {
        System.out.println("[ADM] Storing ADUs in the Data Store, Size:" + adus.size());

        for (final ADU adu : adus) {
            Long largestAduIdReceived = this.stateManager.getLargestADUIdReceived(adu.getAppId());
            logger.log(FINE, "[ADM] Largest id for ADUs received for application " + adu.getAppId() + " is " +
                    largestAduIdReceived);
            if (largestAduIdReceived != null && adu.getADUId() <= largestAduIdReceived) {
                continue;
            }

            try {
                this.dataStoreAdaptor.persistADU(adu);
                logger.log(FINE, "[ADM] Updating Largest ADU id: " + adu.getADUId() + "," + adu.getSource());
                this.stateManager.updateLargestADUIdReceived(adu.getAppId(), adu.getADUId());
                logger.log(FINE, "[ADM] Updated Largest ADU id: " + adu.getADUId() + "," + adu.getSource());
            } catch (IOException e) {
                logger.log(WARNING, "Could not persist adu: " + adu.getADUId(), e);
            }
        }
    }

    public List<ADU> fetchADUs(long initialSize) {
        long cumulativeSize = initialSize;
        List<ADU> res = new ArrayList<>();
        boolean exceededSize = false;
        for (String appId : this.getRegisteredAppIds()) {
            Long largestAduIdDelivered = this.stateManager.getLargestADUIdDeliveredByAppId(appId);
            Long aduIdStart = (largestAduIdDelivered != null) ? (largestAduIdDelivered + 1) : 1;
            List<ADU> adus = this.dataStoreAdaptor.fetchADUs(appId, aduIdStart);
            for (ADU adu : adus) {
                if (adu.getSize() + cumulativeSize > this.APP_DATA_SIZE_LIMIT) {
                    logger.log(FINER, "max data size exceeded");
                    logger.log(INFO, "Unable to add ADUs with id: " + adu.getAppId() + File.separator + adu.getADUId() +
                            " and after into the bundle");
                    exceededSize = true;
                    break;
                }
                res.add(adu);
                logger.log(FINE, "Added ADU: " + adu.getAppId() + File.separator + adu.getADUId() + " to the bundle");
                cumulativeSize += adu.getSize();
            }

            if (exceededSize) break;
        }
        return res;
    }

    public void notifyBundleSent(UncompressedPayload bundle) {
        this.stateManager.registerSentBundleDetails(bundle);
    }

    public Optional<UncompressedPayload.Builder> getLastSentBundleBuilder() {
        return this.stateManager.getLastSentBundleBuilder();
    }
}
