package net.discdd.utils;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import net.discdd.model.ADU;

public class ADUUtils {

    private static final Logger logger = Logger.getLogger(BundleUtils.class.getName());

    public static void writeADU(ADU adu, Path targetDirectory) throws IOException {
        String aduFileName = Long.toString(adu.getADUId());
        var aduFile = targetDirectory.resolve(aduFileName);
        Files.copy(adu.getSource().toPath(), aduFile);
    }

    public static void writeADUs(List<ADU> adus, Path targetDirectory) throws IOException {
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
                logger.info("Reading ADU file: " + aduFileName);
                long aduId;
                try {
                    aduId = Long.parseLong(aduFileName);
                    long size = aduFile.length();
                    ret.add(new ADU(aduFile, appId, aduId, size));
                } catch (NumberFormatException e) {
                    logger.log(INFO, "Ignoring file " + aduFileName + " in " + appSubDirectory);
                }
            }
        }
        ret.sort(Comparator.comparing(ADU::getAppId).thenComparingLong(ADU::getADUId));
        return ret;
    }

    private static Long getADUId(String aduFileName) {
        return Long.valueOf(aduFileName.split("-")[1]);
    }

    public static ADU readADUFromFile(File aduFile, String appId) {
        return new ADU(aduFile, appId, getADUId(aduFile.getName()), aduFile.getTotalSpace());
    }
}
