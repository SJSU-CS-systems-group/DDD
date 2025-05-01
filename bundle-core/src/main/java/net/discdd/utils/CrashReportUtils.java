package net.discdd.utils;

import net.discdd.model.Acknowledgement;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CrashReportUtils {
    private static final Logger logger = Logger.getLogger(CrashReportUtils.class.getName());

    public static String readCrashReportFromFile(Path reportFilePath) {
        try {
            return new String(Files.readAllBytes(reportFilePath));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read the crash report from " + reportFilePath, e);
        }
        return null;
    }

    public static boolean crashReportExists(String reportFilePath) {
        File potentialReport = new File(reportFilePath);
        if (potentialReport.exists()) {
            return true;
        }
        return false;
    }
}
