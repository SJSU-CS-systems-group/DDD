package net.discdd.crashreports;

import static java.util.logging.Level.INFO;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.acra.config.CoreConfiguration;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;
import org.acra.sender.ReportSenderFactory;
import org.jetbrains.annotations.NotNull;

import com.google.auto.service.AutoService;

import android.content.Context;

public class LocalReportSender implements ReportSender {
    static final int MAX_AMOUNT_REPORTS = 5;
    private static final Logger logger = Logger.getLogger(LocalReportSender.class.getName());
    CoreConfiguration config;

    public LocalReportSender(CoreConfiguration coreConfiguration) {
        config = coreConfiguration;
    }

    @Override
    public void send(Context context, CrashReportData errorContent) throws ReportSenderException {
        Path rootDir = context.getApplicationContext().getDataDir().toPath().resolve("to-be-bundled");
        logger.log(INFO, "Directory where acra will send reports to: " + rootDir);
        if (rootDir.toFile().exists()) {
            logger.log(INFO, "We are writing crash report to this devices internal storage");
        } else {
            logger.log(INFO, "We will stop trying to write a crash report to device");
            return;
        }
        File logFile = new File(String.valueOf(rootDir), "crash_report.txt");
        try {
            String reportText = config.getReportFormat()
                    .toFormattedString(errorContent, config.getReportContent(), "\n", "\n\t", false);
            if (logFile.exists()) {
                optimizeReports(logFile);
            }
            FileWriter writer = new FileWriter(logFile, true);
            writer.append("\n");
            writer.append(reportText);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Prepares crash report file for new crash to be appended.
     * Deletes old report if over the max amount have been created.
     *
     * @param logFile the file of crash reports to be optimized
     * @return whether the
     */
    public void optimizeReports(File logFile) throws IOException {
        //looking for how many reports exist in singular reports file
        String reportsFooterTag, lastLineToRemove;
        reportsFooterTag = lastLineToRemove = "SHARED_PREFERENCES=default=empty";
        int reportAmount = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(reportsFooterTag)) {
                    reportAmount++;
                }
            }
        }
        if (reportAmount > MAX_AMOUNT_REPORTS) {
            //create a temp file to copy all reports suceeding first report
            File tempFile = new File(logFile + ".tmp");
            try (BufferedReader br = new BufferedReader(new FileReader(logFile));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                boolean started = false;
                while ((line = br.readLine()) != null) {
                    if (started) {
                        bw.write(line);
                        bw.newLine();
                    } else if (line.contains(lastLineToRemove)) {
                        started = true;
                    }
                }
            }
            //overwrite the original file to match the temporary file
            try (BufferedReader br = new BufferedReader(new FileReader(tempFile));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, false))) {
                String line;
                while ((line = br.readLine()) != null) {
                    bw.write(line);
                    bw.newLine();
                }
            }
            tempFile.delete();
        }
    }

    @AutoService(ReportSenderFactory.class)
    public static class MySenderFactory implements ReportSenderFactory {
        @NotNull
        @Override
        public ReportSender create(@NotNull
        Context context, @NotNull
        CoreConfiguration coreConfiguration) {
            return new LocalReportSender(coreConfiguration);
        }
    }
}
