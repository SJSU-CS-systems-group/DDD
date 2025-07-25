package net.discdd.utils;

import android.content.Context;
import android.content.res.Configuration;
import androidx.annotation.StringRes;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogUtil {
    public static void logUi(Context context,
                             Logger logger,
                             UserLogRepository.UserLogType type,
                             Level level,
                             @StringRes int resId,
                             Object... args) {
        logUi(context, logger, type, level, null, resId, args);
    }

    public static void logUi(Context context,
                             Logger logger,
                             UserLogRepository.UserLogType type,
                             Level level,
                             Throwable throwable,
                             @StringRes int resId,
                             Object... args) {
        var uiString = context.getString(resId, args);
        UserLogRepository.INSTANCE.log(new UserLogRepository.UserLogEntry(type,
                                                                          System.currentTimeMillis(),
                                                                          uiString,
                                                                          level));
        var conf = new Configuration(context.getResources().getConfiguration());
        // we want system logs to be in English
        conf.setLocale(Locale.ENGLISH);
        var englishContext = context.createConfigurationContext(conf);
        var englishString = type + ": " + englishContext.getString(resId, args);
        logger.log(level, englishString);
    }
}
