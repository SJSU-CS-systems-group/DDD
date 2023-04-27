package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.server.bundlesecurity.ServerSecurity;
import com.ddd.utils.ADUUtils;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.Constants;
import com.ddd.utils.JarUtils;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

@Service
public class BundleGeneratorService {

  private static final String BUNDLE_EXTENSION = ".bundle";
  
  public BundleGeneratorService(ServerSecurity serverSecurity) {}

  public UncompressedBundle extractBundle(Bundle bundle, String extractDirPath) {
    String bundleFileName = bundle.getSource().getName();
    String extractedBundlePath =
        extractDirPath
            + File.separator
            + bundleFileName.substring(0, bundleFileName.lastIndexOf('.'));
    JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath);

    File[] payloads = new File(extractedBundlePath + File.separator + "payloads").listFiles();
    EncryptedPayload encryptedPayload = new EncryptedPayload(null, payloads[0]);
    File payloadSign = new File(extractedBundlePath + File.separator + "signatures").listFiles()[0];

    return new UncompressedBundle( // TODO get encryption header, payload signature
        null, new File(extractedBundlePath), null, encryptedPayload, payloadSign);
  }

  public UncompressedPayload extractPayload(Payload payload, String extractDirPath) {
    String extractedPayloadPath = extractDirPath + File.separator + "extracted-payload";
    JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath);

    String ackPath =
        extractedPayloadPath + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
    String aduPath = extractedPayloadPath + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;
    System.out.println("[BGS] ADU Path"+aduPath);
    UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

    builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
    builder.setBundleId(payload.getBundleId());
    builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
    builder.setBundleId(payload.getBundleId());
    builder.setSource(new File(extractedPayloadPath));

    return builder.build();
  }

  public Bundle compressBundle(UncompressedBundle uncompressedBundle, String bundleGenPath) {
    String bundleId = uncompressedBundle.getBundleId();
    File uncompressedBundlePath = uncompressedBundle.getSource();
    File bundleFile = new File(bundleGenPath + File.separator + bundleId + BUNDLE_EXTENSION);
    JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());
    return new Bundle(bundleFile);
  }

  public Payload compressPayload(UncompressedPayload uncompressedPayload, String payloadDirPath) {
    String bundleId = uncompressedPayload.getBundleId();

    File uncompressedPath = uncompressedPayload.getSource();
    File compressedPath =
        new File(
            payloadDirPath
                + File.separator
                + Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME
                + ".jar");
    JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
    return new Payload(bundleId, compressedPath);
  }

  public void writeUncompressedPayload(
      UncompressedPayload uncompressedPayload, File targetDirectory, String bundleFileName) {
    String bundleId = uncompressedPayload.getBundleId();
    String bundleFilePath =
        targetDirectory.getAbsolutePath() + FileSystems.getDefault().getSeparator() + bundleId;

    File bundleFile = new File(bundleFilePath);
    bundleFile.mkdirs();

    String ackPath =
        bundleFilePath
            + FileSystems.getDefault().getSeparator()
            + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;

    File ackRecordFile = new File(ackPath);
    try {
      ackRecordFile.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
    AckRecordUtils.writeAckRecordToFile(uncompressedPayload.getAckRecord(), ackRecordFile);

    String aduPath =
        bundleFilePath
            + FileSystems.getDefault().getSeparator()
            + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    List<ADU> adus = uncompressedPayload.getADUs();

    if (!adus.isEmpty()) {
      File aduDirectory = new File(aduPath);
      aduDirectory.mkdirs();
      ADUUtils.writeADUs(uncompressedPayload.getADUs(), aduDirectory);
    }

    System.out.println(
        "[BundleUtils] Wrote bundle payload with id = " + bundleId + " to " + targetDirectory);
  }

  public static boolean doContentsMatch(
      UncompressedPayload.Builder a, UncompressedPayload.Builder b) {

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

    Comparator<ADU> comp =
        new Comparator<ADU>() {
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
  public static Optional<UncompressedPayload.Builder> bundleStructureToBuilder(
      Map<String, Object> bundleStructureMap) {
    if (bundleStructureMap.isEmpty()) {
      return Optional.empty();
    } else {
      try {
        new TypeToken<Map<String, Object>>() {}.getType();

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
        builder.setAckRecord(
            new Acknowledgement((String) bundleStructureMap.get("acknowledgement")));
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
