package com.ddd.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.ddd.model.ADU;

public class ADUUtils {

    public static void writeADU(ADU adu, File targetDirectory) throws IOException {
        String aduFileName = adu.getAppId() + "-" + adu.getADUId();
        var aduFile = new File(targetDirectory, aduFileName);
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(
                adu.getSource())); BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
                new FileOutputStream(aduFile));) {
                bufferedInputStream.transferTo(bufferedOutputStream);
        }
    }

    public static void writeADUs(List<ADU> adus, File targetDirectory) throws IOException {
        if (adus.isEmpty()) {
            return;
        }
        for (final ADU adu : adus) {
            String appId = adu.getAppId();
            var appDirectory = new File (targetDirectory, appId);
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
        for (final File appSubDirectory : aduDirectory.listFiles(file -> !file.isHidden())) {
            String appId = appSubDirectory.getName();
            for (final File aduFile : appSubDirectory.listFiles(file -> !file.isHidden())) {
                String aduFileName = aduFile.getName();
                long aduId = Long.valueOf(aduFileName.split("-")[1]);
                long size = aduFile.length();
                ret.add(new ADU(aduFile, appId, aduId, size));
            }
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
