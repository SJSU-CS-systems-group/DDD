package net.discdd.bundleclient.viewmodels

import android.app.Activity.MODE_PRIVATE
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.AndroidViewModel
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.bundleclient.MainActivity
import net.discdd.bundleclient.WifiServiceManager
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class MainViewModel(
    application: Application
): AndroidViewModel(application) {
    private val logger = Logger.getLogger(MainActivity::class.java.name)
    private val context get() = getApplication<Application>()

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val sharedPreferences by lazy {
        context.getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    }

    val serviceReady = CompletableFuture<MainActivity>()
}