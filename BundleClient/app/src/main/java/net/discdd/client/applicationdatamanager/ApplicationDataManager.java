package net.discdd.client.applicationdatamanager;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import net.discdd.bundleclient.BundleClientActivity;
import net.discdd.model.ADU;
import net.discdd.model.UncompressedPayload;

import net.discdd.utils.BundleUtils;
import net.discdd.utils.StoreADUs;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class ApplicationDataManager {

    private static final Logger logger = Logger.getLogger(ApplicationDataManager.class.getName());

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    private Context applicationContext;

    /* Database tables */
    private static String SENT_BUNDLE_DETAILS = "Shared/DB/SENT_BUNDLE_DETAILS.json";

    private static String LAST_SENT_BUNDLE_STRUCTURE = "Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

    private Long APP_DATA_SIZE_LIMIT = 1000000000L;

    private static List<String> REGISTER_APP_IDS = List.of("com.example.mysignal", "com.fsck.k9.debug");

    private final Path ROOT_DIR;

    public ApplicationDataManager(Path rootDir) {
        ROOT_DIR = rootDir;
        sendADUsStorage = new StoreADUs(rootDir.resolve("send"));
        receiveADUsStorage = new StoreADUs(rootDir.resolve("receive"));

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
                sendDataToApp(adu);
                logger.log(FINE, "[ADM] Updated Largest ADU id: " + adu.getADUId() + "," + adu.getSource());
            } catch (IOException e) {
                logger.log(WARNING, "Could not persist adu: " + adu.getADUId(), e);
            }
        }
    }

    private void sendDataToApp(ADU adu) throws IOException {
        //notify app that someone sent data for the app
        Intent intent = new Intent("android.intent.dtn.SEND_DATA");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        byte[] data = Files.readAllBytes(adu.getSource().toPath());
        logger.log(FINE, new String(data) + ", Source:" + adu.getSource());
        intent.putExtra(Intent.EXTRA_TEXT, data);
        applicationContext = BundleClientActivity.ApplicationContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent);
        } else {
            logger.log(SEVERE, "[Failed] to send to application. Upgrade Android SDK to 26 or greater");
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
        try {
            Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
            ret = gson.fromJson(new FileReader(ROOT_DIR.resolve(SENT_BUNDLE_DETAILS).toFile()), mapType);
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
}