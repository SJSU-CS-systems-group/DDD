package com.ddd.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

public class BundleUtils {

  private static String readBundleIdFromFile(File bundleIdFile) {
    String bundleId = "";
    try {
      bundleId = Files.readString(Paths.get(bundleIdFile.getAbsolutePath()));
    } catch (IOException e) {
      e.printStackTrace();
    }
    return bundleId;
  }

  private static void writeBundleIdToFile(String bundleId, File bundleIdFile) {
    try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(bundleIdFile))) {
      bufferedWriter.write(bundleId);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /*
   * | bundle
   *    | acknowledgement.txt
   *    | bundle_identifier.txt
   *    | ADU
   *        | signal
   *            | signal-0
   *            | signal-1
   *        | gmail
   *            | gmail-0
   *            | gmail-1
   * */
  //  public static UncompressedPayload.Builder readBundleFromFile(File bundleFile) {
  //    String bundleFileName = bundleFile.getName();
  //    File extractedBundleFile =
  //        new File(
  //            bundleFile.getParent()
  //                + File.separator
  //                + bundleFileName.substring(0, bundleFileName.lastIndexOf('.')));
  //    JarUtils.jarToDir(bundleFile.getAbsolutePath(), extractedBundleFile.getAbsolutePath());
  //
  //    String path = extractedBundleFile.getAbsolutePath();
  //
  //    String ackPath = path + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
  //    String bundleIdPath = path + File.separator + Constants.BUNDLE_IDENTIFIER_FILE_NAME;
  //    String aduPath = path + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;
  //
  //    UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
  //
  //    builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
  //    builder.setBundleId(readBundleIdFromFile(new File(bundleIdPath)));
  //    builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
  //    builder.setSource(extractedBundleFile);
  //
  //    return builder;
  //  }

  public static void writeBundleToFile(
      UncompressedPayload bundle, File targetDirectory, String bundleFileName) {
    String bundleId = bundle.getBundleId();
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
    AckRecordUtils.writeAckRecordToFile(bundle.getAckRecord(), ackRecordFile);

    String bundleIdPath =
        bundleFilePath
            + FileSystems.getDefault().getSeparator()
            + Constants.BUNDLE_IDENTIFIER_FILE_NAME;
    File bundleIdFile = new File(bundleIdPath);
    try {
      bundleIdFile.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    writeBundleIdToFile(bundle.getBundleId(), bundleIdFile);

    String aduPath =
        bundleFilePath
            + FileSystems.getDefault().getSeparator()
            + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    List<ADU> adus = bundle.getADUs();

    if (!adus.isEmpty()) {
      File aduDirectory = new File(aduPath);
      aduDirectory.mkdirs();
      ADUUtils.writeADUs(bundle.getADUs(), aduDirectory);
    }
    //    String jarFilePath = bundleFile.getAbsolutePath() + ".jar";
    //    try {
    //      JarUtils.dirToJar(bundleFile.getAbsolutePath(), jarFilePath);
    //      FileUtils.deleteDirectory(bundleFile);
    //      System.out.println("Folder has been compressed to JAR file.");
    //    } catch (IOException e) {
    //      e.printStackTrace();
    //    }
    System.out.println(
        "[BundleUtils] Wrote bundle with id = " + bundleId + " to " + targetDirectory);
  }

  public static Map<String, Object> getBundleStructureMap(UncompressedPayload bundle) {
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
        aduRange.put(appId, new Long[] {adu.getADUId(), adu.getADUId()});
      }
    }

    lastSentBundleStructure.put("acknowledgement", bundle.getAckRecord().getBundleId());
    lastSentBundleStructure.put("bundle-id", bundle.getBundleId());
    if (!aduRange.isEmpty()) {
      lastSentBundleStructure.put("ADU", aduRange);
    }

    return lastSentBundleStructure;
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
          //          Map<String, List<Object>> aduRange =
          //              (Map<String, List<Object>>) bundleStructureMap.get("ADU");
          Map<String, List<ADU>> aduMap = (Map<String, List<ADU>>) bundleStructureMap.get("ADU");
          for (Map.Entry<String, List<ADU>> entry : aduMap.entrySet()) {
            //          for (Map.Entry<String, List<Object>> entry : aduRange.entrySet()) {
            //            String appId = entry.getKey();
            //            List<Object> range = entry.getValue();
            //            Long min = (Long) range.get(0);
            //            Long max = (Long) range.get(1);
            //            for (Long counter = min; counter <= max; counter++) {
            //              aduList.add(new ADU(null, appId, counter, 0));
            //            }
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

  public static UncompressedBundle extractBundle(Bundle bundle, String extractDirPath) {
    String bundleFileName = bundle.getSource().getName();
    String extractedBundlePath =
        extractDirPath
            + File.separator
            + bundleFileName.substring(0, bundleFileName.lastIndexOf('.'));
    JarUtils.jarToDir(bundle.getSource().getAbsolutePath(), extractedBundlePath);

    File bundleIdFilePath =
        new File(extractedBundlePath + File.separator + Constants.BUNDLE_IDENTIFIER_FILE_NAME);

    String bundleId = readBundleIdFromFile(bundleIdFilePath);

    EncryptedPayload encryptedPayload =
        new EncryptedPayload(
            bundleId,
            new File(
                extractedBundlePath
                    + File.separator
                    + Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME));

    return new UncompressedBundle( // TODO get encryption header, payload signature
        bundleId, new File(extractedBundlePath), null, encryptedPayload, null);
  }

  public static UncompressedPayload extractPayload(Payload payload, String extractDirPath) {
    String extractedPayloadPath = extractDirPath + File.separator + "payload";
    JarUtils.jarToDir(payload.getSource().getAbsolutePath(), extractedPayloadPath);

    String ackPath =
        extractedPayloadPath + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
    String bundleIdPath =
        extractedPayloadPath + File.separator + Constants.BUNDLE_IDENTIFIER_FILE_NAME;
    String aduPath = extractedPayloadPath + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

    builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
    builder.setBundleId(readBundleIdFromFile(new File(bundleIdPath)));
    builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
    builder.setSource(new File(extractedPayloadPath));

    return builder.build();
  }

  public static Bundle compressBundle(UncompressedBundle uncompressedBundle, String bundleGenPath) {
    String bundleId = uncompressedBundle.getBundleId();
    File uncompressedBundlePath = uncompressedBundle.getSource();
    File bundleFile = new File(bundleGenPath + File.separator + bundleId + ".jar");
    JarUtils.dirToJar(uncompressedBundlePath.getAbsolutePath(), bundleFile.getAbsolutePath());
    return new Bundle(bundleFile);
  }

  public static Payload compressPayload(
      UncompressedPayload uncompressedPayload, String payloadDirPath) {
    String bundleId = uncompressedPayload.getBundleId();

    File uncompressedPath = uncompressedPayload.getSource();
    File compressedPath = new File(payloadDirPath + File.separator + bundleId + ".jar");
    JarUtils.dirToJar(uncompressedPath.getAbsolutePath(), compressedPath.getAbsolutePath());
    return new Payload(bundleId, compressedPath);
  }
}
