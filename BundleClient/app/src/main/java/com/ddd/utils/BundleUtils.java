package com.ddd.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FileUtils;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

public class BundleUtils {

  private static String readBundleIdFromFile(File bundleIdFile) {
    String bundleId = "";
    try (BufferedReader bufferedReader = new BufferedReader(new FileReader(bundleIdFile))){
      String line = "";
      while ((line = bufferedReader.readLine()) != null) {
        bundleId = line.trim();
      }
      return bundleId;
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
  public static Bundle.Builder readBundleFromFile(File bundleFile) {
    String bundleFileName = bundleFile.getName();
    File extractedBundleFile =
            new File(
                    bundleFile.getParent()
                            + File.separator
                            + bundleFileName.substring(0, bundleFileName.lastIndexOf('.')));
    JarUtils.jarToDir(bundleFile.getAbsolutePath(), extractedBundleFile.getAbsolutePath());

    String path = extractedBundleFile.getAbsolutePath();

    String ackPath = path + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
    String bundleIdPath = path + File.separator + Constants.BUNDLE_IDENTIFIER_FILE_NAME;
    String aduPath = path + File.separator + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    Bundle.Builder builder = new Bundle.Builder();

    builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
    builder.setBundleId(readBundleIdFromFile(new File(bundleIdPath)));
    builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
    builder.setSource(extractedBundleFile);

    return builder;
  }

  public static void writeBundleToFile(Bundle bundle, File targetDirectory, String bundleFileName) {
    String bundleId = bundle.getBundleId();
    String bundleFilePath =
        targetDirectory.getAbsolutePath() + "/" + bundleId;

    File bundleFile = new File(bundleFilePath);
    bundleFile.mkdirs();

    String ackPath =
        bundleFilePath
            + "/"
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
            + "/"
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
            + "/"
            + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    List<ADU> adus = bundle.getADUs();

    if (!adus.isEmpty()) {
      File aduDirectory = new File(aduPath);
      aduDirectory.mkdirs();
      ADUUtils.writeADUs(bundle.getADUs(), aduDirectory);
    }


    String jarFilePath = bundleFile.getAbsolutePath() + ".jar";
    try {
      JarUtils.dirToJar(bundleFile.getAbsolutePath(), jarFilePath);
      FileUtils.deleteDirectory(bundleFile);
      System.out.println("Folder has been compressed to ZIP file.");
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println(
        "[BundleUtils] Wrote bundle with id = " + bundleId + " to " + targetDirectory);
  }

  public static void writeBundleStructureToJson(Bundle bundle, File jsonFile) {
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
        aduRange.put(appId, new Long[] {adu.getADUId(), adu.getADUId()});
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
  public static Optional<Bundle.Builder> jsonToBundleBuilder(File jsonFile) {
    if (!jsonFile.exists() || (jsonFile.length() == 0)) {
      return Optional.empty();
    } else {
      Gson gson = new Gson();
      Map<String, Object> ret = null;
      try {
        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        ret = gson.fromJson(new FileReader(jsonFile), mapType);

        Bundle.Builder builder = new Bundle.Builder();
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

  public static boolean doContentsMatch(Bundle.Builder a, Bundle.Builder b) {

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
}