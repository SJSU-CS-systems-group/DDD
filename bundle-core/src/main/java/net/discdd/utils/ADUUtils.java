package net.discdd.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import net.discdd.model.ADU;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.FINE;


public class ADUUtils {

    private static final Logger logger = Logger.getLogger(BundleUtils.class.getName());

    public static void writeADU(ADU adu, Path targetDirectory) throws IOException {
        String aduFileName = adu.getAppId() + "-" + adu.getADUId();
        var aduFile = targetDirectory.resolve(aduFileName + ".adu").toFile();
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(
                adu.getSource())); BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
                new FileOutputStream(aduFile))) {
            bufferedInputStream.transferTo(bufferedOutputStream);
        }
    }

    public static void writeADUs(List<ADU> adus, Path targetDirectory) throws IOException {
        if (adus.isEmpty()) {
            return;
        }
        for (final ADU adu : adus) {
            String appId = adu.getAppId();
            var appDirectory = targetDirectory.resolve(appId);

            if (!Files.exists(appDirectory)) {
                Files.createDirectories(appDirectory);
            }
            writeADU(adu, appDirectory);
        }

        logger.log(INFO, "ADUs written to " + targetDirectory);
    }

    public static List<ADU> readADUs(File aduDirectory) {
        logger.log(FINE, "reading ADUs from aduDirectory");
        List<ADU> ret = new ArrayList<>();
        File[] aduFiles = aduDirectory.listFiles();
        if (aduFiles == null) {
            return ret;
        }
        for (final File appSubDirectory : aduDirectory.listFiles(file -> !file.isHidden())) {
            String appId = appSubDirectory.getName();
            for (final File aduFile : appSubDirectory.listFiles(file -> !file.isHidden())) {
                String aduFileName = aduFile.getName();
                long aduId = Long.parseLong((aduFileName.split("-")[1]).replace(".adu",""));
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
