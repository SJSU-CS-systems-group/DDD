package net.discdd.utils;

import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BundleUtils {

    private static final Logger logger = Logger.getLogger(BundleUtils.class.getName());

    private static final String BUNDLE_EXTENSION = ".bundle";

    public static void writeBundleStructureToJson(UncompressedPayload bundle, File jsonFile) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> lastSentBundleStructure = new HashMap<>();

        Map<String, Long[]> aduRange = new HashMap<>();

        for (ADU adu : bundle.getADUs()) {
            String appId = adu.getAppId();
            if (aduRange.containsKey(appId)) {
                Long[] limits = aduRange.get(appId);
                limits[0] = Math.min(limits[0], adu.getADUId());
                limits[1] = Math.max(limits[1], adu.getADUId());
                aduRange.put(adu.getAppId(), limits);
            } else {
                aduRange.put(appId, new Long[] { adu.getADUId(), adu.getADUId() });
            }
        }

        lastSentBundleStructure.put("acknowledgement", bundle.getAckRecord().getBundleId());
        lastSentBundleStructure.put("bundle-id", bundle.getBundleId());
        if (!aduRange.isEmpty()) {
            lastSentBundleStructure.put("ADU", aduRange);
        }
        String jsonString = gson.toJson(lastSentBundleStructure);
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(jsonString);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<UncompressedPayload.Builder> jsonToBundleBuilder(File jsonFile) {
        if (!jsonFile.exists() || (jsonFile.length() == 0)) {
            return Optional.empty();
        } else {
            Gson gson = new Gson();
            Map<String, Object> ret = null;
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                ret = gson.fromJson(new FileReader(jsonFile), mapType);

                UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
                builder.setAckRecord(new Acknowledgement((String) ret.get("acknowledgement")));
                builder.setBundleId((String) ret.get("bundle-id"));
                if (ret.containsKey("ADU")) {
                    List<ADU> aduList = new ArrayList<>();
                    Map<String, List<Object>> aduRange = (Map<String, List<Object>>) ret.get("ADU");
                    for (Map.Entry<String, List<Object>> entry : aduRange.entrySet()) {
                        String appId = entry.getKey();
                        List<Object> range = entry.getValue();
                        Long min = ((Double) range.get(0)).longValue();
                        Long max = ((Double) range.get(1)).longValue();
                        for (Long counter = min; counter <= max; counter++) {
                            aduList.add(new ADU(null, appId, counter, 0));
                        }
                    }
                    builder.setADUs(aduList);
                    builder.setSource(null);
                }
                return Optional.of(builder);
            } catch (JsonSyntaxException e) {
                e.printStackTrace();
                return Optional.empty();
            } catch (JsonIOException e) {
                e.printStackTrace();
                return Optional.empty();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return Optional.empty();
            }
        }
    }

    public static boolean doContentsMatch(UncompressedPayload.Builder a, UncompressedPayload.Builder b) {
        logger.log(FINE, "comparing payload builders");
        Acknowledgement aAckRecord = a.getAckRecord();
        Acknowledgement bAckRecord = b.getAckRecord();

        logger.log(FINE, "comparing acknowledgements");
        if (aAckRecord == null || bAckRecord == null) {
            return false;
        }
        if (!aAckRecord.equals(bAckRecord)) {
            return false;
        }

        logger.log(FINE, "comparing ADUs");
        List<ADU> aADUs = a.getADUs();
        List<ADU> bADUs = b.getADUs();

        if (aADUs == null || bADUs == null) {
            return false;
        }
        if (aADUs.size() != bADUs.size()) {
            return false;
        }

        logger.log(FINE, "sorting ADUs");
        Collections.sort(aADUs);
        Collections.sort(bADUs);
        for (int i = 0; i < aADUs.size(); i++) {
            if (!aADUs.get(i).equals(bADUs.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static void writeUncompressedPayload(UncompressedPayload uncompressedPayload, File targetDirectory,
                                                String bundleFileName) {
        String bundleId = uncompressedPayload.getBundleId();
        String bundleFilePath = targetDirectory.getAbsolutePath() + "/" + bundleId;
        logger.log(INFO, "Writing uncompressed payload to path: " + bundleFilePath);

        File bundleFile = new File(bundleFilePath);
        if (!bundleFile.exists()) {
            bundleFile.mkdirs();
        }
        String ackPath = bundleFilePath + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;

        File ackRecordFile = new File(ackPath);
        if (!ackRecordFile.exists()) {
            try {
                ackRecordFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        AckRecordUtils.writeAckRecordToFile(uncompressedPayload.getAckRecord(), ackRecordFile);

        String aduPath = bundleFilePath + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;

        List<ADU> adus = uncompressedPayload.getADUs();

        if (!adus.isEmpty()) {
            File aduDirectory = new File(aduPath);
            aduDirectory.mkdirs();
            ADUUtils.writeADUs(uncompressedPayload.getADUs(), aduDirectory);
        }

        logger.log(INFO, "[BundleUtils] Wrote bundle payload with id = " + bundleId + " to " + targetDirectory);
    }

    public static Payload compressPayload(UncompressedPayload uncompressedPayload, Path payloadDirPath) {
        String bundleId = uncompressedPayload.getBundleId();
        logger.log(INFO, "Compressing payload for bundleId: " + bundleId);

        File uncompressedPath = uncompressedPayload.getSource();
        File compressedPath =
                new File(payloadDirPath + File.separator + Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar");
        JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
        return new Payload(bundleId, compressedPath);
    }

    public static Bundle compressBundle(UncompressedBundle uncompressedBundle, String bundleGenPath) {
        String bundleId = uncompressedBundle.getBundleId();
        logger.log(INFO, "Compressing bundle for bundleId: " + bundleId);
        File uncompressedBundlePath = uncompressedBundle.getSource();
        File bundleFile = new File(bundleGenPath + File.separator + bundleId + BUNDLE_EXTENSION);
        JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());
        return new Bundle(bundleFile);
    }

    public static UncompressedBundle extractBundle(Bundle bundle, String extractDirPath) {
        String bundleFileName = bundle.getSource().getName();
        logger.log(INFO, "Extracting bundle for bundle name: " + bundleFileName);
        String extractedBundlePath =
                extractDirPath + File.separator + bundleFileName.substring(0, bundleFileName.lastIndexOf('.'));
        JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath);

        File[] payloads = new File(extractedBundlePath + File.separator + "payloads").listFiles();
        EncryptedPayload encryptedPayload = new EncryptedPayload(null, payloads[0]);
        File payloadSign = new File(extractedBundlePath + File.separator + "signatures").listFiles()[0];

        return new UncompressedBundle( // TODO get encryption header, payload signature and get bundle id from BS
                                       null, new File(extractedBundlePath), null, encryptedPayload, payloadSign);
    }

    public static UncompressedPayload extractPayload(Payload payload, String extractDirPath) {
        String extractedPayloadPath = extractDirPath + File.separator + "extracted-payload";
        logger.log(INFO, "Extracting payload for payload path: " + extractedPayloadPath);
        JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath);

        String ackPath = extractedPayloadPath + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
        String aduPath = extractedPayloadPath + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

        builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
        builder.setBundleId(payload.getBundleId());
        builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
        builder.setBundleId(payload.getBundleId());
        builder.setSource(new File(extractedPayloadPath));

        return builder.build();
    }
}
