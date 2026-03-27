package net.discdd.crashreports;

import android.content.Context;

import com.google.auto.service.AutoService;
import org.acra.config.CoreConfiguration;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;
import org.acra.sender.ReportSenderFactory;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class LocalReportSender implements ReportSender {
    private static final Logger logger = Logger.getLogger(LocalReportSender.class.getName());
    static final int MAX_AMOUNT_REPORTS = 5;

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
//        } else {
//            logger.log(INFO, "We will stop trying to write a crash report to device");
//            return;
        }
        int currIndex;
        try {
            currIndex = optimizeReports(toBeBundledDir);
        } catch (IOException e) {
            throw new RuntimeException(e); //TODO: no runtime excepts
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
        //looking for how many reports exist in dir
        AtomicInteger num = new AtomicInteger(); //change name
        logger.log(INFO, "ACRA: About to start counting num reports in dir");
        Files.walk(reportsDir).forEach(file -> {
            if (file.getFileName().toString().startsWith("crash_report")) {
                num.getAndIncrement();
                logger.log(INFO, "ACRA: Num reports (and counting possibly): " + num.getAcquire());
            }
        });
        if (num.getAcquire() >= MAX_AMOUNT_REPORTS) {
            logger.log(INFO, "ACRA: Max num reports read, deleting oldest");
            //rewrite file name with number after "crash_report" - 1
            // if currChar == 1, delete old crash report
            Files.walk(reportsDir).sorted().forEach(file -> { //sort b/c walk doesn't guarantee order in which dir is traversed
                if (file.getFileName().toString().startsWith("crash_report")) {
                    int indexToReplace = 12; // Index 12 is the crash report number MAKE FINAL
                    char currChar = file.getFileName().toString().charAt(12);
                    int currNum;
                    try {
                        currNum = Character.getNumericValue(currChar);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Crash report is not written in format we expect");
                    }
                    int newNum = currNum - 1;
                    char newChar = (char) ('0' + newNum);

                    if (newNum != 0) {
                        StringBuilder builder = new StringBuilder(file.getFileName().toString());
                        builder.setCharAt(indexToReplace, newChar);
                        String modified = builder.toString();
                        try {
                            Object params = new String[] { file.toFile().getName(), file.getParent().resolve(modified).toString() };
                            logger.log(INFO, "Optimizing crash reports moving the file {0} to {1}", params);
                            Files.move(file, file.getParent().resolve(modified), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            logger.log(SEVERE, "Optimizing crash reports unsuccessfully attempted to move directory");
                        }
                    } else {
                        if (file.toFile().delete()) {
                            logger.log(INFO, "Optimizing crash reports successfully deleted the file: " + file.toFile().getName());
                        }
                    }
                }
            });
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
