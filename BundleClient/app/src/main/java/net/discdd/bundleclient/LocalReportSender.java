package net.discdd.bundleclient;

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
        Path rootDir = context.getApplicationContext().getExternalFilesDir(null).toPath();
        File logFile = new File(String.valueOf(rootDir), "crash_report.txt");

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

    @AutoService(ReportSenderFactory.class)
    public static class MySenderFactory implements ReportSenderFactory {
        @NotNull
        @Override
        public ReportSender create(@NotNull Context context, @NotNull CoreConfiguration coreConfiguration) {
            return new LocalReportSender(coreConfiguration);
        }
    }
}
