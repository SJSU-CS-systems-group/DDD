package net.discdd.utils;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import org.apache.commons.io.FileUtils;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;

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
                                                String bundleFileName) throws IOException {
        String bundleId = uncompressedPayload.getBundleId();
        Path bundleFilePath = targetDirectory.toPath().resolve(bundleId);
        logger.log(INFO, "Writing uncompressed payload to path: " + bundleFilePath);

        File bundleFile = bundleFilePath.toFile();
        if (!bundleFile.exists()) {
            bundleFile.mkdirs();
        }

        Path ackPath = bundleFilePath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);

        File ackRecordFile = ackPath.toFile();
        if (!ackRecordFile.exists()) {
            try {
                ackRecordFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        AckRecordUtils.writeAckRecordToFile(uncompressedPayload.getAckRecord(), ackRecordFile);

        Path aduPath = bundleFilePath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);

        List<ADU> adus = uncompressedPayload.getADUs();

        if (!adus.isEmpty()) {
            File aduDirectory = aduPath.toFile();
            aduDirectory.mkdirs();
            ADUUtils.writeADUs(uncompressedPayload.getADUs(), aduDirectory);
        }

        logger.log(INFO, "[BundleUtils] Wrote bundle payload with id = " + bundleId + " to " + targetDirectory);
    }

    public static Payload compressPayload(UncompressedPayload uncompressedPayload, Path payloadDirPath) {
        String bundleId = uncompressedPayload.getBundleId();
        logger.log(INFO, "Compressing payload for bundleId: " + bundleId);

        File uncompressedPath = uncompressedPayload.getSource();
        File compressedPath = payloadDirPath.resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar").toFile();
        JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
        return new Payload(bundleId, compressedPath);
    }

    public static Bundle compressBundle(UncompressedBundle uncompressedBundle, Path bundleGenPath) {
        String bundleId = uncompressedBundle.getBundleId();
        logger.log(INFO, "Compressing bundle for bundleId: " + bundleId);
        File uncompressedBundlePath = uncompressedBundle.getSource();
        File bundleFile = bundleGenPath.resolve(bundleId + BUNDLE_EXTENSION).toFile();
        JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());
        return new Bundle(bundleFile);
    }

    public static UncompressedBundle extractBundle(Bundle bundle, Path extractDirPath) {
        String bundleFileName = bundle.getSource().getName();
        logger.log(INFO, "Extracting bundle for bundle name: " + bundleFileName);
        Path extractedBundlePath = extractDirPath.resolve(bundleFileName.substring(0, bundleFileName.lastIndexOf('.')));
        JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath.toString());

        File[] payloads = extractedBundlePath.resolve("payloads").toFile().listFiles();
        EncryptedPayload encryptedPayload = new EncryptedPayload(null, payloads[0]);
        File payloadSign = extractedBundlePath.resolve("signatures").toFile().listFiles()[0];

        return new UncompressedBundle( // TODO get encryption header, payload signature and get bundle id from BS
                                       null, extractedBundlePath.toFile(), null, encryptedPayload, payloadSign);
    }

    public static UncompressedPayload extractPayload(Payload payload, Path extractDirPath) throws IOException {
        Path extractedPayloadPath = extractDirPath.resolve("extracted-payload");
        logger.log(INFO, "Extracting payload for payload path: " + extractedPayloadPath);
        JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath.toString());

        Path ackPath = extractedPayloadPath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        Path aduPath = extractedPayloadPath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

        builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(ackPath.toFile()));
        builder.setBundleId(payload.getBundleId());
        builder.setADUs(ADUUtils.readADUs(aduPath.toFile()));
        builder.setBundleId(payload.getBundleId());
        builder.setSource(extractedPayloadPath.toFile());

        return builder.build();
    }
}
