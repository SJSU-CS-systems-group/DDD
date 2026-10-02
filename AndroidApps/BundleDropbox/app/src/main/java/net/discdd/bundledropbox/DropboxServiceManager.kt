package net.discdd.bundledropbox

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.bundledropbox.service.DropboxService
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Operator actions the UI can invoke on the running dropbox. `DropboxService` is expected to
 * implement this interface (`public class DropboxService extends Service implements DropboxControls`).
 *
 * It exists so the UI compiles independently of the service wiring: the UI only reaches the service
 * through [DropboxServiceManager.controls], which returns `null` until the bound service implements it.
 *
 * Threading: the UI calls every method from a background coroutine (`Dispatchers.IO`), never from the
 * main thread, so implementations may block briefly; they must be thread-safe and should hand real
 * work to the service's own executor / the role-cycle controller's single thread.
 */
interface DropboxControls {
    /** Stops the role cycle (radio idle, `CycleMode.STOPPED`) until [resumeCycle]. Idempotent. */
    fun pauseCycle()

    /** Restarts the role cycle after [pauseCycle]. Idempotent. */
    fun resumeCycle()

    /** @return true while the operator has the cycle paused (STOPPED alone also means "not started"). */
    fun isCyclePaused(): Boolean

    /** Ends the current HOST dwell now (normal drain rules apply) and starts a transport visit. */
    fun forceVisitNow()

    /** Ends the current VISIT dwell now (normal drain rules apply) and starts hosting clients. */
    fun forceHostNow()

    /** Operator "clear deposited": deletes every UPSTREAM (client-to-server) bundle from the store. */
    fun clearUpstream()

    /** Operator "clear pickups": deletes every DOWNSTREAM (server-to-client) bundle from the store. */
    fun clearDownstream()

    /**
     * NEARBY_WIFI_DEVICES (and/or ACCESS_FINE_LOCATION) was just granted; retry radio work that failed
     * for lack of permission. Mirrors `BundleTransportService.wifiPermissionGranted()`.
     */
    fun wifiPermissionGranted()
}

/**
 * Process-wide handle on the bound [DropboxService], mirroring BundleTransport's
 * `TransportServiceManager`: the activity passes [connection] to `bindService`, view models read
 * [getService] / [controls], and code that must wait for the first bind uses [serviceReady].
 *
 * Unlike the transport, the activity never stops the service when it is destroyed: the dropbox is
 * a stationary relay that keeps cycling with the screen off.
 */
object DropboxServiceManager {
    private val logger = Logger.getLogger(DropboxServiceManager::class.java.name)

    // Written on the main thread by ServiceConnection callbacks, read from IO coroutines.
    @Volatile
    private var service: DropboxService? = null

    @Volatile
    private var warnedNoControls = false

    /** Completes with the service on the first successful bind (never reset, as in the transport). */
    val serviceReady = CompletableFuture<DropboxService>()

    private val _connected = MutableStateFlow(false)

    /** true while the activity is bound to the service; screens use it to enable action buttons. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Pass to `bindService(intent, DropboxServiceManager.connection, BIND_AUTO_CREATE)`. */
    val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val dropboxService = (binder as DropboxService.DropboxBinder).service
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

    /** @return the bound service, or `null` before the bind completes / after unbinding. */
    fun getService(): DropboxService? = service

    /**
     * @return the operator controls of the bound service, or `null` if not bound or if the service
     *         does not (yet) implement [DropboxControls]
     */
    fun controls(): DropboxControls? {
        val s = service ?: return null
        val c = s as? DropboxControls
        if (c == null && !warnedNoControls) {
            warnedNoControls = true
            logger.log(Level.WARNING, "DropboxService does not implement DropboxControls; UI actions are disabled")
        }
        return c
    }
}
