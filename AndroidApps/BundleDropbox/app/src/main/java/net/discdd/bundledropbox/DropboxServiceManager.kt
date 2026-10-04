package net.discdd.bundledropbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.bundledropbox.service.DropboxService
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Operator actions the UI can invoke on the running dropbox, implemented by `DropboxService`. The UI
 * reaches them only through [DropboxServiceManager.controls], which keeps screens and view models
 * independent of the service class and easy to fake.
 *
 * Threading: the UI calls every method from a background coroutine (`Dispatchers.IO`), never from the
 * main thread, so implementations may block briefly; they must be thread-safe and should hand real
 * work to the service's own executor / the role-cycle controller's single thread.
 */
interface DropboxControls {
    /**
     * Stops the role cycle (radio idle, `CycleMode.STOPPED`) until [resumeCycle]. Idempotent. Persisted:
     * the cycle stays paused across restarts, reboots and app updates.
     */
    fun pauseCycle()

    /** Restarts the role cycle after [pauseCycle]. Idempotent. */
    fun resumeCycle()

    /** @return true while the operator has the cycle paused (STOPPED alone also means "not started"). */
    fun isCyclePaused(): Boolean

    /**
     * Operator "Stop dropbox": idles the radio and ends the foreground service. It stays stopped (no start at
     * boot, after an update or when the app opens) until [DropboxServiceManager.startRelay]. Does not wait
     * for the service to finish starting.
     */
    fun stopRelay()

    /** Ends the current HOST dwell now (normal drain rules apply) and starts a transport visit. */
    fun forceVisitNow()

    /** Ends the current VISIT dwell now (normal drain rules apply) and starts hosting clients. */
    fun forceHostNow()

    /** Operator "clear deposited": deletes every UPSTREAM (client-to-server) bundle from the store. */
    fun clearUpstream()

    /** Operator "clear pickups": deletes every DOWNSTREAM (server-to-client) bundle from the store. */
    fun clearDownstream()

    /**
     * Operator "reset recency blob" (audit M6): forgets the stored recency blob, so the dropbox stops
     * advertising and serving it and goes back to the bootstrap state (VISIT only) until a carrier supplies
     * a blob signed by the pinned server. Ends a HOST dwell early. Manual recovery from a bad blob; an
     * untrusted blob is already dropped automatically when the service starts.
     */
    fun resetRecencyBlob()

    /**
     * Settings "Server keys (QR)": trust the DDD server named by a scanned or pasted
     * `https://discdd.net/srvr?host=..&port=..&identity=..&signedpre=..&ratchet=..` URL (the one BundleClient
     * scans). Re-pins the recency blob check and re-creates the dropbox's control endpoint, which then
     * appears to that server as a new client.
     *
     * @return operator-facing description of the keys now trusted
     * @throws IllegalArgumentException if the text is not a server URL or holds an invalid key
     * @throws IllegalStateException if the service is not ready or the keys cannot be saved
     */
    fun importServerKeys(url: String): String

    /**
     * Forgets imported server keys and trusts android-core's built-in ones again (same re-wiring as
     * [importServerKeys]).
     *
     * @return operator-facing description of the keys now trusted
     */
    fun useBuiltInServerKeys(): String

    /**
     * NEARBY_WIFI_DEVICES was just granted; retry radio work that failed for lack of permission. Mirrors
     * `BundleTransportService.wifiPermissionGranted()`. May throw IllegalStateException if the service
     * failed to start or is still starting; callers must catch it.
     */
    fun wifiPermissionGranted()
}

/**
 * Process-wide handle on the bound [DropboxService], mirroring BundleTransport's
 * `TransportServiceManager`: the activity passes [connection] to `bindService`, view models read
 * [getService] / [controls], and code that must wait for the first bind uses [serviceReady].
 *
 * Unlike the transport, the activity never stops the service when it is destroyed: the dropbox is
 * a stationary relay that keeps cycling with the screen off. Only the operator's Stop button does.
 *
 * Bindings are reference-counted ([onActivityBound] / [onActivityUnbound]): if two activity instances
 * exist (e.g. one opened from the notification), destroying one must not disconnect the other's UI
 * (audit m11).
 */
object DropboxServiceManager {
    private val logger = Logger.getLogger(DropboxServiceManager::class.java.name)

    // Written on the main thread by ServiceConnection callbacks, read from IO coroutines.
    @Volatile
    private var service: DropboxService? = null

    /** Activity instances currently bound with [connection] (main thread only). */
    private var bindings = 0

    /** Completes with the service on the first successful bind (never reset, as in the transport). */
    val serviceReady = CompletableFuture<DropboxService>()

    private val _connected = MutableStateFlow(false)

    /** true while the activity is bound to the service; screens use it to enable action buttons. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Pass to `bindService(intent, DropboxServiceManager.connection, BIND_AUTO_CREATE)`. */
    val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val dropboxService = (binder as DropboxService.LocalBinder).service
            setService(dropboxService)
            serviceReady.complete(dropboxService)
        }

        override fun onServiceDisconnected(className: ComponentName) {
            clearService()
        }
    }

    fun setService(s: DropboxService) {
        logger.info("Setting dropbox service: $s")
        service = s
        _connected.value = true
    }

    fun clearService() {
        logger.info("Clearing dropbox service")
        service = null
        _connected.value = false
    }

    /** An activity bound [connection] successfully. Main thread. */
    fun onActivityBound() {
        bindings++
    }

    /**
     * An activity is about to unbind [connection]. Clears the handle only when it was the last one; other
     * instances keep a working UI. Main thread.
     */
    fun onActivityUnbound() {
        bindings = (bindings - 1).coerceAtLeast(0)
        if (bindings == 0) clearService()
    }

    /**
     * Operator "Start dropbox" after [DropboxControls.stopRelay]. The [DropboxService.ACTION_START] command
     * is the only start that clears the persisted stop.
     */
    fun startRelay(context: Context) {
        val intent = Intent(context, DropboxService::class.java).setAction(DropboxService.ACTION_START)
        context.applicationContext.startForegroundService(intent)
    }

    /** @return the bound service, or `null` before the bind completes / after unbinding. */
    fun getService(): DropboxService? = service

    /** @return the operator controls of the bound service, or `null` before the bind completes / after unbinding */
    fun controls(): DropboxControls? = service
}
