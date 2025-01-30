package net.discdd.crashreports;

import static org.acra.ACRA.log;
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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class LocalReportSender implements ReportSender {
    CoreConfiguration config;

    public LocalReportSender(CoreConfiguration coreConfiguration) {
        config = coreConfiguration;
    }

    @Override
    public void send(Context context, CrashReportData errorContent) throws ReportSenderException {
        Path rootDir = context.getApplicationContext().getExternalFilesDir(null).toPath();
        Path destDir = rootDir.resolve("BundleTransmission/server");
        if (destDir.toFile().exists()) {
            log.i("ACRA local reports sender ", "We are writing crash report to transport device storage");
            rootDir = destDir;
        } else {
            log.i("ACRA local reports sender ", "We are writing crash report to client device storage");
        }
        File logFile = new File(String.valueOf(rootDir), "crash_report.txt");
        try {
            // Use the core ReportFormat configuration
            String reportText = config.getReportFormat()
                    .toFormattedString(errorContent, config.getReportContent(), "\n", "\n\t", false);

            // Overwrite last report
            optimizeReports(logFile);
            FileWriter writer = new FileWriter(logFile, false);
            writer.append(reportText);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Does something when we have reported over five crashes
     */
    public void optimizeReports (File logFile) throws IOException {
        String reportsFooterTag, lastLineToRemove;
        reportsFooterTag = lastLineToRemove = "SHARED_PREFERENCES=default=empty";
        int reportAmount = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals(reportsFooterTag)) {
                    reportAmount++;
                }
            }
        }
        if (reportAmount >= 5) {
            File tempFile = new File(logFile + ".tmp");
            try (BufferedReader br = new BufferedReader(new FileReader(logFile));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                boolean started = false;
                while ((line = br.readLine()) != null) {
                    if (started) {
                        bw.write(line);
                        bw.newLine();
                    } else if (line.equals(lastLineToRemove)) {
                        started = true;
                    }
                }
            }
            if (!logFile.delete()) {
                throw new IOException("Could not delete original file");
            }
            if (!tempFile.renameTo(logFile)) {
                throw new IOException("Could not rename temporary file");
            }
        }
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
