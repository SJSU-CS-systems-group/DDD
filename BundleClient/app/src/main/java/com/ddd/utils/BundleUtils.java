package com.ddd.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FileUtils;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;

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
  public static Bundle.Builder readBundleFromFile(File inputFile) {
    String path = inputFile.getAbsolutePath();

    String ackPath =
        path + FileSystems.getDefault().getSeparator() + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
    String bundleIdPath =
        path + FileSystems.getDefault().getSeparator() + Constants.BUNDLE_IDENTIFIER_FILE_NAME;
    String aduPath =
        path + FileSystems.getDefault().getSeparator() + Constants.BUNDLE_ADU_DIRECTORY_NAME;

    Bundle.Builder builder = new Bundle.Builder();

    builder.setAckRecord(AckRecordUtils.readAckRecordFromFile(new File(ackPath)));
    builder.setBundleId(readBundleIdFromFile(new File(bundleIdPath)));
    builder.setADUs(ADUUtils.readADUs(new File(aduPath)));
    builder.setSource(inputFile);

    return builder;
  }

  public static void zipFolder(String folderPath, String zipFilePath) throws IOException {
    FileOutputStream fos = new FileOutputStream(zipFilePath);
    ZipOutputStream zos = new ZipOutputStream(fos);
    addFolderToZip(folderPath, folderPath, zos);
    zos.close();
  }

  private static void addFolderToZip(String rootFolderPath, String folderPath, ZipOutputStream zos)
      throws IOException {
    File folder = new File(folderPath);
    for (String fileName : folder.list()) {
      File file = new File(folder, fileName);
      if (file.isDirectory()) {
        String subFolderPath = file.getAbsolutePath();
        addFolderToZip(rootFolderPath, subFolderPath, zos);
      } else {
        String relativePath = folderPath.substring(rootFolderPath.length() + 1);
        String zipEntryPath = relativePath + File.separator + fileName;
        FileInputStream fis = new FileInputStream(file);
        ZipEntry zipEntry = new ZipEntry(zipEntryPath);
        zos.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
          zos.write(bytes, 0, length);
        }
        zos.closeEntry();
        fis.close();
      }
    }
  }

  public static void writeBundleToFile(Bundle bundle, File targetDirectory, String bundleFileName) {
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

    String zipFilePath = bundleFile.getAbsolutePath() + ".zip";
    try {
      zipFolder(bundleFile.getAbsolutePath(), zipFilePath);
      FileUtils.deleteDirectory(bundleFile);
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println(
        "[BundleUtils] Wrote bundle with id = " + bundleId + " to " + targetDirectory);
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
