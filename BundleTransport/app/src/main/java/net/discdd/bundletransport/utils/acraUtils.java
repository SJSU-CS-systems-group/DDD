package net.discdd.bundletransport.utils;

import android.app.Application;
import android.content.Context;

import org.acra.ACRA;
import org.acra.BuildConfig;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.data.CrashReportData;
import org.acra.data.StringFormat;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;

public class acraUtils extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        ACRA.init(this, new CoreConfigurationBuilder()
                //core configuration:
                .withBuildConfigClass(BuildConfig.class)
                .withReportFormat(StringFormat.JSON)
        );
    }
}