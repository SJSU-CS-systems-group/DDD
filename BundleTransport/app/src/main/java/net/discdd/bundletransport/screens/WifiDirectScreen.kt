package net.discdd.bundletransport.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundletransport.BundleTransportActivity
import net.discdd.bundletransport.TransportWifiDirectService
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import net.discdd.theme.ComposableTheme
import java.util.concurrent.CompletableFuture

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
            //Should be rewritten with wifiInfoVie

            var checked by remember {
                mutableStateOf(
                    preferences.getBoolean(
                        TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE,
                        true
                    )
                )
            }

            Text(text = state.wifiInfoView)
            Text(text = state.wifiStatusView)
            Text(text = state.clientLogView)
            Text(text = "Device Name: ${state.deviceNameView}")

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

            Text(text = "Interactions with BundleClients")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiDirectScreenPreview() {
    WifiDirectScreen(serviceReadyFuture = CompletableFuture())
}

