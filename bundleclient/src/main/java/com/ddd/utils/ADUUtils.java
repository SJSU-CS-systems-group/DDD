package com.ddd.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import com.ddd.model.ADU;

public class ADUUtils {

  public static void writeADU(ADU adu, File targetDirectory) {
    String aduFileName = adu.getAppId() + "-" + adu.getADUId();
    File aduFile =
        new File(
            targetDirectory.getAbsolutePath()
                + FileSystems.getDefault().getSeparator()
                + aduFileName);

    try (BufferedInputStream bufferedInputStream =
            new BufferedInputStream(new FileInputStream(adu.getSource()));
        BufferedOutputStream bufferedOutputStream =
            new BufferedOutputStream(new FileOutputStream(aduFile)); ) {

      int nbytes = 0;
      byte[] buffer = new byte[1024];
      while ((nbytes = bufferedInputStream.read(buffer)) > 0) {
        bufferedOutputStream.write(buffer, 0, nbytes);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void writeADUs(List<ADU> adus, File targetDirectory) {
    if (adus.isEmpty()) {
      return;
    }

    for (final ADU adu : adus) {
      String appId = adu.getAppId();
      File appDirectory =
          new File(targetDirectory + FileSystems.getDefault().getSeparator() + appId);
      if (!appDirectory.exists()) {
        appDirectory.mkdirs();
      }
      writeADU(adu, appDirectory);
    }
  }

  public static List<ADU> readADUs(File aduDirectory) {
    List<ADU> ret = new ArrayList<>();
    File[] aduFiles = aduDirectory.listFiles();
    if (aduFiles == null) {
      return ret;
    }

    for (final File appDir : aduDirectory.listFiles()) {
      String appId = appDir.getName();
      for (final File aduFile : appDir.listFiles()) {
        String aduFileName = aduFile.getName();
        long aduId = Long.valueOf(aduFileName.split("-")[1]);
        long size = aduFile.length();
        ret.add(new ADU(aduFile, appId, aduId, size));
      }
    }

    return ret;
  }

  public static List<ADU> readADUs(File aduDirectory, String appId) {
    List<ADU> ret = new ArrayList<>();
    File[] aduFiles = aduDirectory.listFiles();
    if (aduFiles == null) {
      return ret;
    }
    File appSubDirectory =
        new File(aduDirectory.getAbsolutePath() + FileSystems.getDefault().getSeparator() + appId);

    for (final File aduFile : appSubDirectory.listFiles()) {
      String aduFileName = aduFile.getName();
      long aduId = Long.valueOf(aduFileName.split("-")[1]);
      long size = aduFile.length();
      ret.add(new ADU(aduFile, appId, aduId, size));
    }

    return ret;
  }

  private static Long getADUId(String aduFileName) {
    return Long.valueOf(aduFileName.split("-")[1]);
  }

  public static ADU readADUFromFile(File aduFile, String appId) {
    return new ADU(aduFile, appId, getADUId(aduFile.getName()), aduFile.getTotalSpace());
  }
}
