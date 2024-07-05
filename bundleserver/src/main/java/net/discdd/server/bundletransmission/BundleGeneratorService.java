package net.discdd.server.bundletransmission;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.bundlesecurity.ServerSecurity;
import net.discdd.utils.ADUUtils;
import net.discdd.utils.AckRecordUtils;
import net.discdd.utils.Constants;
import net.discdd.utils.JarUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Service
public class BundleGeneratorService {
    private static final Logger logger = Logger.getLogger(BundleGeneratorService.class.getName());

    private static final String BUNDLE_EXTENSION = ".bundle";

    public BundleGeneratorService(ServerSecurity serverSecurity) {}

    public UncompressedBundle extractBundle(Bundle bundle, Path extractDirPath) {
        String bundleFileName = bundle.getSource().getName();
        Path extractedBundlePath = extractDirPath.resolve(bundleFileName.substring(0, bundleFileName.lastIndexOf('.')));
        JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath.toString());

        File[] payloads = extractedBundlePath.resolve("payloads").toFile().listFiles();
        EncryptedPayload encryptedPayload = new EncryptedPayload(null, payloads[0]);
        File payloadSign = extractedBundlePath.resolve("signatures").toFile().listFiles()[0];

        return new UncompressedBundle( // TODO get encryption header, payload signature
                                       null, extractedBundlePath.toFile(), null, encryptedPayload, payloadSign);
    }

    public UncompressedPayload extractPayload(Payload payload, Path extractDirPath) {
        Path extractedPayloadPath = extractDirPath.resolve("extracted-payload");
        JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath.toString());

        Path ackPath = extractedPayloadPath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        Path aduPath = extractedPayloadPath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);
        logger.log(INFO, "[BundleGeneratorService] ADU Path" + aduPath);
        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

        builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(ackPath.toFile()));
        builder.setBundleId(payload.getBundleId());
        builder.setADUs(ADUUtils.readADUs(aduPath.toFile()));
        builder.setBundleId(payload.getBundleId());
        builder.setSource(extractedPayloadPath.toFile());

        return builder.build();
    }

    public Bundle compressBundle(UncompressedBundle uncompressedBundle, Path bundleGenPath) {
        String bundleId = uncompressedBundle.getBundleId();
        File uncompressedBundlePath = uncompressedBundle.getSource();
        File bundleFile = bundleGenPath.resolve(bundleId + BUNDLE_EXTENSION).toFile();
        JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());
        return new Bundle(bundleFile);
    }

    public Payload compressPayload(UncompressedPayload uncompressedPayload, Path payloadDirPath) {
        String bundleId = uncompressedPayload.getBundleId();

        File uncompressedPath = uncompressedPayload.getSource();
        File compressedPath = payloadDirPath.resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar").toFile();
        JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
        return new Payload(bundleId, compressedPath);
    }

    public void writeUncompressedPayload(UncompressedPayload uncompressedPayload, File targetDirectory,
                                         String bundleFileName) {
        String bundleId = uncompressedPayload.getBundleId();
        Path bundleFilePath = targetDirectory.toPath().resolve(bundleId);

        if (!bundleFilePath.toFile().exists()) {
            bundleFilePath.toFile().mkdirs();
        }

        Path ackPath = bundleFilePath.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);

        File ackRecordFile = ackPath.toFile();
        try {
            ackRecordFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        AckRecordUtils.writeAckRecordToFile(uncompressedPayload.getAckRecord(), ackRecordFile);

        Path aduPath = bundleFilePath.resolve(Constants.BUNDLE_ADU_DIRECTORY_NAME);

        List<ADU> adus = uncompressedPayload.getADUs();

        if (!adus.isEmpty()) {
            File aduDirectory = aduPath.toFile();

            if (!aduDirectory.exists()) {
                aduDirectory.mkdirs();
            }

            ADUUtils.writeADUs(uncompressedPayload.getADUs(), aduDirectory);
        }

        logger.log(INFO, "[BundleUtils] Wrote bundle payload with id = " + bundleId + " to " + targetDirectory);
    }

    public static boolean doContentsMatch(UncompressedPayload.Builder a, UncompressedPayload.Builder b) {

        Acknowledgement aAckRecord = a.getAckRecord();
        Acknowledgement bAckRecord = b.getAckRecord();

        if ((aAckRecord == null) ^ (bAckRecord == null)) {
            return false;
        }
        if (!aAckRecord.equals(bAckRecord)) {
            return false;
        }

        List<ADU> aADUs = a.getADUs();
        List<ADU> bADUs = b.getADUs();

        if ((aADUs == null) ^ (bADUs == null)) {
            return false;
        }
        if (aADUs.size() != bADUs.size()) {
            return false;
        }

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
                e.printStackTrace();
                return Optional.empty();
            } catch (JsonIOException e) {
                e.printStackTrace();
                return Optional.empty();
            }
        }
    }
}
