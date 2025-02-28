package net.discdd.bundleclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundleclient.viewmodels.ServerViewModel
import net.discdd.viewmodels.ConnectivityViewModel

class ServerFrag: Fragment() {
    private lateinit var viewModel: ServerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ServerViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setWifiService((requireActivity() as BundleClientActivity).wifiBgService)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    ServerScreen(viewModel)
                }
            }
        }
    }

    fun setWifiService(service: BundleClientWifiDirectService) {
        if (::viewModel.isInitialized) {
            viewModel.setWifiService(service)
        }
    }
}

@Composable
fun ServerScreen(
    serverViewModel: ServerViewModel,
    connectivityViewModel: ConnectivityViewModel = viewModel()
) {
    val serverState by serverViewModel.state.collectAsState()
    val connectivityState by connectivityViewModel.state.collectAsState()
    var enableConnectBtn by remember { mutableStateOf(false) }

    LaunchedEffect(serverState.domain, serverState.port, connectivityState.networkConnected) {
        val enable = serverState.domain.isNotEmpty() && serverState.port.isNotEmpty() && connectivityState.networkConnected
        enableConnectBtn = enable
    }
    LaunchedEffect(serverState.message) {
        if (serverState.message != null) {
            delay(2000)
            serverViewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = serverState.domain,
            onValueChange = { serverViewModel.onDomainChanged(it) },
            label = { Text("BundleServer Domain") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        OutlinedTextField(
            value = serverState.port,
            onValueChange = { serverViewModel.onPortChanged(it) },
            label = { Text("Port Input") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        FilledTonalButton(onClick = {
            serverViewModel.connectServer()
        },
            enabled = enableConnectBtn,
            modifier = Modifier.fillMaxWidth()) {
            Text("Connect to Bundle Server")
        }
        FilledTonalButton(onClick = {
            serverViewModel.saveDomainPort()
        },
            modifier = Modifier.fillMaxWidth()) {
            Text("Save Domain and Port")
        }
        serverState.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
