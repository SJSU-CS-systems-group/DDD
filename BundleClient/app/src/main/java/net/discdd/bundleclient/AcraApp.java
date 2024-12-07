package net.discdd.bundleclient;

import android.app.Application;
import android.content.Context;

import org.acra.ACRA;
import org.acra.BuildConfig;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.data.StringFormat;

public class AcraApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ACRA.init(this, new CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig.class).withReportFormat(StringFormat.KEY_VALUE_LIST));
    }
}
