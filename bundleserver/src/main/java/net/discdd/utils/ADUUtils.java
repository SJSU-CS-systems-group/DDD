package net.discdd.utils;

import net.discdd.model.ADU;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ADUUtils {

    public static void writeADU(ADU adu, File targetDirectory) {
        String aduFileName = adu.getAppId() + "-" + adu.getADUId();
        File aduFile = targetDirectory.toPath().resolve(aduFileName).toFile();

        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(
                adu.getSource())); BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
                new FileOutputStream(aduFile));) {

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
            File appDirectory = targetDirectory.toPath().resolve(appId).toFile();
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
        for (final File appSubDirectory : aduDirectory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return !file.isHidden();
            }
        })) {
            String appId = appSubDirectory.getName();
            for (final File aduFile : appSubDirectory.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return !file.isHidden();
                }
            })) {
                String aduFileName = aduFile.getName();
                long aduId = Long.valueOf(aduFileName.split("-")[1]);
                long size = aduFile.length();
                ret.add(new ADU(aduFile, appId, aduId, size));
            }
        }
        return ret;
    }
}
