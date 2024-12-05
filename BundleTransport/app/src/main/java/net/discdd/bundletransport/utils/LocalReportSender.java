package net.discdd.bundletransport.utils;

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
import java.nio.file.Path;

public class LocalReportSender implements ReportSender {
    CoreConfiguration config;

    public LocalReportSender(CoreConfiguration coreConfiguration) {
        config = coreConfiguration;
    }

    @Override
    public void send(Context context, CrashReportData errorContent) throws ReportSenderException {
        // Iterate over the CrashReportData instance and do whatever
        // you need with each pair of ReportField key / String value

        //declare root dir and append target dir (toServer)
        Path rootDir = context.getApplicationContext().getExternalFilesDir(null).toPath();
        Path destDir = rootDir.resolve("BundleTransmission/server");
        if (!destDir.toFile().exists()) {
            destDir.toFile().mkdir();
        }
        File logFile = new File(String.valueOf(destDir), "crash_report.txt");
        try {
            // Use the core ReportFormat configuration
            String reportText = config.getReportFormat()
                    .toFormattedString(errorContent,
                                       config.getReportContent(), "\n", "\n\t", false);

            // Overwrite last report
            FileWriter writer = new FileWriter(logFile, false);
            writer.append(reportText);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
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
