package net.discdd.crashreports;

import static org.acra.ACRA.log;

import org.acra.ACRA;
import org.acra.BuildConfig;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.data.StringFormat;

import android.app.Application;
import android.content.Context;

public class AcraApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ACRA.DEV_LOGGING = true;
        log.e("ACRA Enabled dev logging", "ACRA OK");
        ACRA.init(this,
                  new CoreConfigurationBuilder().withBuildConfigClass(BuildConfig.class)
                          .withReportFormat(StringFormat.KEY_VALUE_LIST));
    }
}
