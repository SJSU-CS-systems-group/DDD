package net.discdd.bundledropbox;

import android.content.Context;
import android.content.SharedPreferences;

import net.discdd.bundledropbox.cycle.CycleConfig;

/**
 * SharedPreferences keys and defaults for the dropbox Settings tab (03 Architecture "Settings").
 *
 * <p>Same pattern as the transport: the UI writes these preferences and {@code DropboxService} reacts
 * through an {@code OnSharedPreferenceChangeListener} (e.g. pushing a new {@link CycleConfig} into
 * {@code RoleCycleController.updateConfig}). Durations are stored in whole seconds for the UI and
 * converted to milliseconds here, so {@link CycleConfig} itself stays free of Android types.
 */
public final class DropboxPrefs {
    /** Name of the dropbox's SharedPreferences file. */
    public static final String PREFS_NAME = "net.discdd.bundledropbox.PREFS";

    /** HOSTING (client-serving) dwell, seconds. */
    public static final String KEY_CLIENT_PERIOD_S = "client_period_s";
    /** VISITING (transport search/exchange) dwell, seconds. */
    public static final String KEY_TRANSPORT_PERIOD_S = "transport_period_s";
    /** Drain cap after a dwell expires, seconds. */
    public static final String KEY_DRAIN_CAP_S = "drain_cap_s";
    /** End VISIT early after a successful carrier exchange. */
    public static final String KEY_RETURN_AFTER_VISIT = "return_after_visit";
    /**
     * Bump the advertised transport id after each successful visit so clients' "new data" filter
     * (blobTimestamp &gt; lastExchange per transportId) sees a fresh transport (Research/R3).
     */
    public static final String KEY_ROTATE_ADVERTISED_ID = "rotate_advertised_id";
    /** Store capacity, megabytes. */
    public static final String KEY_MAX_STORAGE_MB = "max_storage_mb";
    /**
     * Hold a partial wake lock while the service runs, so the cycle keeps running with the screen off (no
     * Wi-Fi lock: see {@code DropboxService#applyKeepAwake}).
     */
    public static final String KEY_KEEP_AWAKE = "keep_awake";
    /**
     * Start the dropbox service when the phone finishes booting ({@code service/BootReceiver}), so an
     * unattended relay comes back after a power cut or OS update without anyone opening the app.
     */
    public static final String KEY_START_ON_BOOT = "start_on_boot";

    public static final int DEFAULT_CLIENT_PERIOD_S = (int) (CycleConfig.DEFAULT_CLIENT_PERIOD_MS / 1000);
    public static final int DEFAULT_TRANSPORT_PERIOD_S = (int) (CycleConfig.DEFAULT_TRANSPORT_PERIOD_MS / 1000);
    public static final int DEFAULT_DRAIN_CAP_S = (int) (CycleConfig.DEFAULT_DRAIN_CAP_MS / 1000);
    public static final boolean DEFAULT_RETURN_AFTER_VISIT = CycleConfig.DEFAULT_RETURN_AFTER_VISIT;
    public static final boolean DEFAULT_ROTATE_ADVERTISED_ID = true;
    public static final int DEFAULT_MAX_STORAGE_MB = 1024;
    public static final boolean DEFAULT_KEEP_AWAKE = false;
    public static final boolean DEFAULT_START_ON_BOOT = true;

    private DropboxPrefs() {
    }

    /**
     * @param context any context
     * @return the dropbox's private SharedPreferences
     */
    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Builds a {@link CycleConfig} from preferences. Out-of-range values (dwell &lt; 1 s, drain cap &lt; 0)
     * fall back to the defaults instead of throwing, so a bad setting can never stop the relay.
     *
     * @param prefs the dropbox preferences
     * @return the configured cycle timers
     */
    public static CycleConfig cycleConfig(SharedPreferences prefs) {
        int client = prefs.getInt(KEY_CLIENT_PERIOD_S, DEFAULT_CLIENT_PERIOD_S);
        int transport = prefs.getInt(KEY_TRANSPORT_PERIOD_S, DEFAULT_TRANSPORT_PERIOD_S);
        int drain = prefs.getInt(KEY_DRAIN_CAP_S, DEFAULT_DRAIN_CAP_S);
        boolean returnAfterVisit = prefs.getBoolean(KEY_RETURN_AFTER_VISIT, DEFAULT_RETURN_AFTER_VISIT);
        return new CycleConfig(client >= 1 ? client * 1000L : CycleConfig.DEFAULT_CLIENT_PERIOD_MS,
                               transport >= 1 ? transport * 1000L : CycleConfig.DEFAULT_TRANSPORT_PERIOD_MS,
                               drain >= 0 ? drain * 1000L : CycleConfig.DEFAULT_DRAIN_CAP_MS,
                               returnAfterVisit);
    }

    /**
     * @param prefs the dropbox preferences
     * @return store capacity in bytes (defaults to {@link #DEFAULT_MAX_STORAGE_MB} if unset or &lt; 1)
     */
    public static long maxStorageBytes(SharedPreferences prefs) {
        int mb = prefs.getInt(KEY_MAX_STORAGE_MB, DEFAULT_MAX_STORAGE_MB);
        return (mb >= 1 ? mb : DEFAULT_MAX_STORAGE_MB) * 1024L * 1024L;
    }
}
