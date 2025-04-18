package net.discdd.bundletransport.screens

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

import java.util.concurrent.CompletableFuture

import net.discdd.bundletransport.BundleTransportActivity
import net.discdd.bundletransport.TransportWifiDirectService
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import net.discdd.theme.ComposableTheme

class WifiDirectFragment: Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposableTheme {
                    WifiDirectScreen(serviceReadyFuture = (activity as BundleTransportActivity).serviceReady())
                }
            }
        }
    }

}

@Composable
fun WifiDirectScreen(
    wifiViewModel: WifiDirectViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<TransportWifiDirectService>,
    preferences: SharedPreferences = LocalContext.current.getSharedPreferences(
        TransportWifiDirectService.WIFI_DIRECT_PREFERENCES,
        Context.MODE_PRIVATE
    )
) {
    val state by wifiViewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {

        LaunchedEffect(Unit) {
            wifiViewModel.initialize(serviceReadyFuture)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> wifiViewModel.registerBroadcastReceiver()
                    Lifecycle.Event.ON_PAUSE -> wifiViewModel.unregisterBroadcastReceiver()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            var checked by remember {
                mutableStateOf(
                    preferences.getBoolean(
                        TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE,
                        true
                    )
                )
            }

            val nameValid by remember {
                derivedStateOf { state.deviceName.startsWith("ddd_") }
            }

            Text(
                text = state.wifiInfo,
                modifier = Modifier.clickable {showDialog = true}
            )
            if (showDialog == true) {
                var gi = wifiViewModel.getService()?.groupInfo
                var connectedPeers: ArrayList<String> = ArrayList<String>()
                if (gi != null) {
                    gi.clientList.forEach {c -> connectedPeers.add(c.deviceName)}
                }
                AlertDialog(
                    title = { Text(text = "Connected Devices") },
                    text = { Text(text = connectedPeers.toTypedArray().joinToString(", ")) },
                    onDismissRequest = { showDialog = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDialog = false
                            }
                        ) {
                            Text("Dismiss")
                        }
                    }
                )
            }

            Text(text = "Wifi Status: ${state.wifiStatus}")
            Text(text = "Device Name: ${state.deviceName}")

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        preferences.edit().putBoolean(
                            TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE,
                            it
                        ).apply()
                    }
                )
                Text(text = "Collect data even when app is closed")
            }

            // only show the name change button if we don't have a valid device name
            // (transports must have device names starting with ddd_)
            if (!nameValid) {
                Text(text = "Phone name must start with ddd_")

                FilledTonalButton(
                    onClick = {wifiViewModel.openInfoSettings()},
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Change Phone Name")
                }
            }

            Text(text = "Interactions with BundleClients: ")
            Text(text = state.clientLog)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiDirectScreenPreview() {
    WifiDirectScreen(serviceReadyFuture = CompletableFuture())
}

