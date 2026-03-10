package net.discdd.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_FILENAME;
import static net.discdd.bundlesecurity.DDDPEMEncoder.createEncodedPublicKeyBytes;
import static net.discdd.bundlesecurity.DDDPEMEncoder.createEncryptedEncodedPublicKeyBytes;

public class BundleUtils {
    private static final Logger logger = Logger.getLogger(BundleUtils.class.getName());
    private static final String BUNDLE_EXTENSION = ".bundle";
    private static final String stringToMatch = "^[a-zA-Z0-9-_=]+$";

    public static UncompressedBundle extractBundle(Bundle bundle, Path extractDirPath) throws IOException {
        String bundleFileName = bundle.getSource().getName();
        logger.log(INFO, "Extracting bundle for bundle name: " + bundleFileName);
        Path extractedBundlePath = extractDirPath.resolve(bundleFileName);
        JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath.toString());
        File[] payloads = extractedBundlePath.resolve("payloads").toFile().listFiles();
        EncryptedPayload encryptedPayload = new EncryptedPayload(null, payloads[0]);
        return new UncompressedBundle( // TODO get encryption header, payload signature and get bundle id from BS
                                       null, extractedBundlePath.toFile(), null, encryptedPayload);
    }

    public static UncompressedPayload extractPayload(Payload payload, Path extractDirPath) throws IOException {
        var extractedPayloadPath = extractDirPath.resolve("extracted-payload");

        if (!Files.exists(extractedPayloadPath)) {
            Files.createDirectories(extractedPayloadPath);
        }

        logger.log(INFO, "Extracting payload for payload path: " + extractedPayloadPath);
        JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath.toString());

        Path ackPath = extractedPayloadPath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        Path aduPath = extractedPayloadPath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);

        logger.log(INFO, "ADU Path: " + aduPath);

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

        builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(ackPath));
        builder.setBundleId(payload.getBundleId());
        builder.setADUs(ADUUtils.readADUs(aduPath.toFile()));
        builder.setBundleId(payload.getBundleId());
        builder.setSource(extractedPayloadPath.toFile());

        return builder.build();
    }

    public static Bundle compressBundle(UncompressedBundle uncompressedBundle, Path bundleGenPath) {
        String bundleId = uncompressedBundle.getBundleId();
        logger.log(INFO, "Compressing bundle for bundleId: " + bundleId);
        File uncompressedBundlePath = uncompressedBundle.getSource();
        File bundleFile = bundleGenPath.resolve(bundleId + BUNDLE_EXTENSION).toFile();
        JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());

        return new Bundle(bundleFile);
    }

    public static Payload compressPayload(UncompressedPayload uncompressedPayload, Path payloadDirPath) {
        String bundleId = uncompressedPayload.getBundleId();

        File uncompressedPath = uncompressedPayload.getSource();
        File compressedPath = payloadDirPath.resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar").toFile();
        JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
        return new Payload(bundleId, compressedPath);
    }

    public static void writeUncompressedPayload(UncompressedPayload uncompressedPayload,
                                                File targetDirectory,
                                                String bundleFileName) throws IOException {
        String bundleId = uncompressedPayload.getBundleId();
        Path bundleFilePath = targetDirectory.toPath().resolve(bundleId);

        logger.log(INFO, "Writing uncompressed payload to path: " + bundleFilePath);

        File bundleFile = bundleFilePath.toFile();
        if (!bundleFile.exists()) {
            bundleFile.mkdirs();
        }

        Path ackPath = bundleFilePath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);

        var ackRecordFile = ackPath;
        AckRecordUtils.writeAckRecordToFile(uncompressedPayload.getAckRecord(), ackRecordFile);

        Path aduPath = bundleFilePath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);

        List<ADU> adus = uncompressedPayload.getADUs();

        if (!adus.isEmpty()) {
            if (!Files.exists(aduPath)) {
                Files.createDirectories(aduPath);
            }

            try {
                logger.log(INFO, "[BundleUtils] Writing ADUs to " + aduPath);
                ADUUtils.writeADUs(uncompressedPayload.getADUs(), aduPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        logger.log(INFO, "[BundleUtils] Wrote bundle payload with id = " + bundleId + " to " + targetDirectory);
    }

    public static boolean doContentsMatch(UncompressedPayload.Builder a, UncompressedPayload.Builder b) {
        logger.log(FINE, "comparing payload builders");
        Acknowledgement aAckRecord = a.getAckRecord();
        Acknowledgement bAckRecord = b.getAckRecord();

        logger.log(FINE, "comparing acknowledgements");
        if ((aAckRecord == null) ^ (bAckRecord == null)) {
            return false;
        }
        if (!aAckRecord.equals(bAckRecord)) {
            return false;
        }

        logger.log(FINE, "comparing ADUs");
        List<ADU> aADUs = a.getADUs();
        List<ADU> bADUs = b.getADUs();

        if ((aADUs == null) ^ (bADUs == null)) {
            return false;
        }
        if (aADUs.size() != bADUs.size()) {
            return false;
        }

        logger.log(FINE, "sorting ADUs");
        Comparator<ADU> comp = new Comparator<ADU>() {
            @Override
            public int compare(ADU a, ADU b) {
                int ret = a.getAppId().compareTo(b.getAppId());
                if (ret == 0) {
                    ret = (int) (a.getADUId() - b.getADUId());
                }
                return ret;
            }
        };

        Collections.sort(aADUs, comp);
        Collections.sort(bADUs, comp);
        for (int i = 0; i < aADUs.size(); i++) {
            if (!aADUs.get(i).equals(bADUs.get(i))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Optional<UncompressedPayload.Builder> bundleStructureToBuilder(Map<String, Object> bundleStructureMap) {
        if (bundleStructureMap.isEmpty()) {
            return Optional.empty();
        } else {
            try {
                new TypeToken<Map<String, Object>>() {}.getType();

                UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
                builder.setAckRecord(new Acknowledgement((String) bundleStructureMap.get("acknowledgement")));
                builder.setBundleId((String) bundleStructureMap.get("bundle-id"));
                if (bundleStructureMap.containsKey("ADU")) {
                    List<ADU> aduList = new ArrayList<>();
                    Map<String, List<ADU>> aduMap = (Map<String, List<ADU>>) bundleStructureMap.get("ADU");
                    for (Map.Entry<String, List<ADU>> entry : aduMap.entrySet()) {
                        List<ADU> adus = entry.getValue();
                        for (ADU adu : adus) {
                            aduList.add(adu);
                        }
                    }
                    builder.setADUs(aduList);
                    builder.setSource(null);
                }
                return Optional.of(builder);
            } catch (JsonSyntaxException e) {
                logger.log(SEVERE, "Failed to parse bundle structure map", e);
                return Optional.empty();
            } catch (JsonIOException e) {
                logger.log(SEVERE, "Failed to process bundle structure map", e);
                return Optional.empty();
            }
        }
    }

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
            logger.log(SEVERE, "Failed to write bundle JSON to " + jsonFile.getAbsolutePath(), e);
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
                    aduList.sort(Comparator.comparing(ADU::getAppId).thenComparingLong(ADU::getADUId));
                    builder.setADUs(aduList);
                    builder.setSource(null);
                }
                return Optional.of(builder);
            } catch (JsonSyntaxException e) {
                logger.log(SEVERE, "Failed to parse bundle JSON from " + jsonFile.getAbsolutePath(), e);
                return Optional.empty();
            } catch (JsonIOException e) {
                logger.log(SEVERE, "Failed to read bundle JSON from " + jsonFile.getAbsolutePath(), e);
                return Optional.empty();
            } catch (FileNotFoundException e) {
                logger.log(SEVERE, "Bundle JSON file not found: " + jsonFile.getAbsolutePath(), e);
                return Optional.empty();
            }
        }
    }

    public static void createBundlePayloadForAdus(List<ADU> adus,
                                                  byte[] routingData,
                                                  String ackedEncryptedBundleId,
                                                  String crashReport,
                                                  OutputStream outputStream) throws IOException,
            NoSuchAlgorithmException {
        try (DDDJarFileCreator innerJar = new DDDJarFileCreator(outputStream)) {
            if (ackedEncryptedBundleId == null) ackedEncryptedBundleId = "HB";
            logger.log(INFO, "[BU/createBundlePayload] " + adus.size());
            // add the records to the inner jar
            innerJar.createEntry("acknowledgement.txt", ackedEncryptedBundleId.getBytes());
            innerJar.createEntry("routing.metadata", routingData == null ? "{}".getBytes() : routingData);
            if (crashReport != null) {
                innerJar.createEntry("crash_report.txt", crashReport.getBytes());
            }

            for (var adu : adus) {
                try (var os = innerJar.createEntry(Paths.get(Constants.BUNDLE_ADU_DIRECTORY_NAME,
                                                             adu.getAppId(),
                                                             Long.toString(adu.getADUId())));
                     var aos = Files.newInputStream(adu.getSource().toPath(), StandardOpenOption.READ)) {
                    aos.transferTo(os);
                }
            }
        }
    }

    public static void encryptPayloadAndCreateBundle(Encrypter payloadEncryptor,
                                                     ECPublicKey clientIdentityPublicKey,
                                                     ECPublicKey clientBaseKeyPairPublicKey,
                                                     ECPublicKey serverIdentityPublicKey,
                                                     String encryptedBundleId,
                                                     InputStream payloadStream,
                                                     OutputStream outputStream) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidMessageException {

        DDDJarFileCreator outerJar = new DDDJarFileCreator(outputStream);

        var os = outerJar.createEntry(Paths.get(PAYLOAD_DIR, PAYLOAD_FILENAME));
        // encrypt the payload
        payloadEncryptor.encrypt(payloadStream, os);

        // store the bundleId
        outerJar.createEntry(SecurityUtils.BUNDLEID_FILENAME, encryptedBundleId.getBytes());

        // store the keys
        try {
            outerJar.createEntry(SecurityUtils.CLIENT_IDENTITY_KEY,
                                 createEncryptedEncodedPublicKeyBytes(clientIdentityPublicKey,
                                                                      serverIdentityPublicKey));
        } catch (GeneralSecurityException e) {
            throw new IOException("General Security Exception", e);
        }
        outerJar.createEntry(SecurityUtils.CLIENT_BASE_KEY, createEncodedPublicKeyBytes(clientBaseKeyPairPublicKey));
        outerJar.createEntry(SecurityUtils.SERVER_IDENTITY_KEY, createEncodedPublicKeyBytes(serverIdentityPublicKey));

        // bundle is ready
        outerJar.close();
    }

    public static Future<?> runFuture(ExecutorService executorService,
                                      String ackedEncryptedBundleId,
                                      String crashReport,
                                      List<ADU> adus,
                                      byte[] routingData,
                                      PipedInputStream inputPipe) throws IOException {
        PipedOutputStream outputPipe = new PipedOutputStream(inputPipe);
        Future<?> future = executorService.submit(() -> {
            try {
                BundleUtils.createBundlePayloadForAdus(adus,
                                                       routingData,
                                                       ackedEncryptedBundleId,
                                                       crashReport,
                                                       outputPipe);
            } catch (IOException | NoSuchAlgorithmException e) {
                return e;
            } finally {
                outputPipe.close();
            }
            return null;
        });
        return future;
    }

    public interface Encrypter {
        void encrypt(InputStream payload, OutputStream outputStream) throws IOException, NoSuchAlgorithmException,
                InvalidKeyException, InvalidMessageException;
    }

    public static void checkIdClean(String s) {
        // [a-zA-Z0-9+-] matches alphanumeric characters or + or -
        Pattern p = Pattern.compile(stringToMatch);
        final Matcher m = p.matcher(s);
        if (!m.matches() || s.length() > 100) {
            logger.log(WARNING, "Invalid ID: " + s);
            throw new InvalidParameterException("Not URL Encoded");
        }
    }
}
