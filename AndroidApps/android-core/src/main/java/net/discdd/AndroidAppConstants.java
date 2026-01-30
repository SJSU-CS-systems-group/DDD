package net.discdd;

import java.util.logging.Logger;

import android.content.SharedPreferences;

public class AndroidAppConstants {
    public static final Logger logger = Logger.getLogger(AndroidAppConstants.class.getName());
    public static final String BUNDLE_SERVER_DOMAIN = "ravlykmail.com";
    public static final int BUNDLE_SERVER_PORT = 7778;

    public static void checkDefaultDomainPortSettings(SharedPreferences sharedPref) {
        if (sharedPref == null) {
            logger.severe("SharedPreferences is unexpectedly null cannot set defaults");
            return;
        }
        if (sharedPref.getString("domain", "").isEmpty()) {
            sharedPref.edit().putString("domain", BUNDLE_SERVER_DOMAIN).apply();
        }
        if (sharedPref.getInt("port", 0) == 0) {
            sharedPref.edit().putInt("port", BUNDLE_SERVER_PORT).apply();
        }
    }
}
