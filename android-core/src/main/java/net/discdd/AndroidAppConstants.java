package net.discdd;

import android.content.SharedPreferences;

public class AndroidAppConstants {
    public static final String BUNDLE_SERVER_DOMAIN = "ravlykmail.com";
    public static final int BUNDLE_SERVER_PORT = 7778;

    public static void checkDefaultDomainPortSettings(SharedPreferences sharedPref) {
        if (sharedPref.getString("domain", "").isEmpty()) {
            sharedPref.edit().putString("domain", BUNDLE_SERVER_DOMAIN).apply();
        }
        if (sharedPref.getInt("port", 0) == 0) {
            sharedPref.edit().putInt("port", BUNDLE_SERVER_PORT).apply();
        }
    }
}
