package net.discdd.crashreports;

import android.content.Context;

import com.google.auto.service.AutoService;
import org.acra.config.CoreConfiguration;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;
import org.acra.sender.ReportSenderFactory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class LocalReportSender implements ReportSender {
    private static final Logger logger = Logger.getLogger(LocalReportSender.class.getName());
    static final int MAX_AMOUNT_REPORTS = 5;
    private static final Pattern CRASH_REPORT_PATTERN = Pattern.compile("^crash_report(\\d+)\\.txt$");
    CoreConfiguration config;

    public LocalReportSender(CoreConfiguration coreConfiguration) {
        config = coreConfiguration;
    }

    @Override
    public void send(Context context, CrashReportData errorContent) throws ReportSenderException {
        Path toBeBundledDir = context.getApplicationContext().getDataDir().toPath().resolve("to-be-bundled");
        logger.log(INFO, "Directory where acra will send reports to: " + toBeBundledDir);
        if (!toBeBundledDir.toFile().exists()) {
            toBeBundledDir.toFile().mkdir();
        }
        int currIndex;
        try {
            currIndex = optimizeReports(toBeBundledDir);
        } catch (IOException e) {
            logger.log(SEVERE, "Optimizing reports on this device failed" + e);
            return;
        }
        File logFile = new File(String.valueOf(toBeBundledDir), "crash_report" + currIndex + ".txt");
        try {
            String reportText = config.getReportFormat()
                    .toFormattedString(errorContent, config.getReportContent(), "\n", "\n\t", false);
            FileWriter writer = new FileWriter(logFile, false);
            writer.append(reportText);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Prepares to-be-bundled dir for new crash report file.
     * Deletes and renames old report if over the max amount have been created.
     *
     * @param reportsDir the dir of crash reports to be optimized
     * @return next available index
     */
    public int optimizeReports(Path reportsDir) throws IOException {
        AtomicInteger num = new AtomicInteger();
        logger.log(INFO, "ACRA: About to start counting num reports in dir");
        try (var files = Files.walk(reportsDir)) {
            files.forEach(file -> {
                if (CRASH_REPORT_PATTERN.matcher(file.getFileName().toString()).matches()) {
                    num.getAndIncrement();
                    logger.log(INFO, "ACRA: Num reports (and counting possibly): " + num.getAcquire());
                }
            });
        }
        if (num.getAcquire() >= MAX_AMOUNT_REPORTS) {
            logger.log(INFO, "ACRA: Max num reports read, deleting oldest");
            try (var files = Files.walk(reportsDir)) {
                files.sorted().forEach(file -> {
                    Matcher matcher = CRASH_REPORT_PATTERN.matcher(file.getFileName().toString());
                    if (!matcher.matches()) return; // skip legacy (crash_report.txt) and malformed files
                    int currNum = Integer.parseInt(matcher.group(1));
                    int newNum = currNum - 1;
                    if (newNum != 0) {
                        String modified = "crash_report" + newNum + ".txt";
                        try {
                            logger.log(INFO, "Optimizing crash reports moving the file " + file.toFile().getName() + " to " + file.getParent().resolve(modified));
                            Files.move(file, file.getParent().resolve(modified), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            logger.log(SEVERE, "Optimizing crash reports unsuccessfully attempted to move directory");
                        }
                    } else {
                        if (file.toFile().delete()) {
                            logger.log(INFO, "Optimizing crash reports successfully deleted the file: " + file.toFile().getName());
                        }
                    }
                });
            }
            return MAX_AMOUNT_REPORTS;
        }
        return num.getAcquire() + 1;
    }

    @AutoService(ReportSenderFactory.class)
    public static class MySenderFactory implements ReportSenderFactory {
        @NotNull
        @Override
        public ReportSender create(@NotNull Context context, @NotNull CoreConfiguration coreConfiguration) {
            return new LocalReportSender(coreConfiguration);
        }
    }
}
