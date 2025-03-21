package net.discdd.bundletransport.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundletransport.viewmodels.ServerUploadViewModel
import net.discdd.viewmodels.ConnectivityViewModel

class UploadFragment: Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    ServerUploadScreen()
                }
            }
        }
    }
}

@Composable
fun ServerUploadScreen(
    uploadViewModel: ServerUploadViewModel = viewModel(),
    connectivityViewModel: ConnectivityViewModel = viewModel()
) {
    val uploadState by uploadViewModel.state.collectAsState()
    val connectivityState by connectivityViewModel.state.collectAsState()
    var connectServerBtn by remember { mutableStateOf(false) }

    LaunchedEffect(uploadState.domain, uploadState.port, connectivityState.networkConnected) {
        val enable = uploadState.domain.isNotEmpty() && uploadState.port.isNotEmpty() && connectivityState.networkConnected
        connectServerBtn = enable
    }
    LaunchedEffect(uploadState.message) {
        if (uploadState.message != null) {
            delay(5000)
            uploadViewModel.clearMessage()
        }
    }

    Column (
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = {uploadViewModel.connectServer()},
            enabled = connectServerBtn,
            modifier = Modifier.fillMaxWidth()) {
            Text("Connect to Bundle Server")
        }
        OutlinedTextField(
            value = uploadState.domain,
            onValueChange = {uploadViewModel.onDomainChanged(it)},
            label = { Text("Domain Input") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        OutlinedTextField(
            value = uploadState.port,
            onValueChange = {uploadViewModel.onPortChanged(it)},
            label = { Text("Port Input") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        FilledTonalButton(onClick = {uploadViewModel.saveDomainPort()},
            modifier = Modifier.fillMaxWidth()) {
            Text("Save Domain and Port")
        }
        FilledTonalButton(onClick = {uploadViewModel.restoreDomainPort()},
            modifier = Modifier.fillMaxWidth()) {
            Text("Restore Domain and Port")
        }
        Row (
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "toClient: ",
                fontSize = 20.sp
            )
            Text (
                text = uploadState.clientCount,
                fontSize = 20.sp
            )
            Text(
                text = "    toServer: ",
                fontSize = 20.sp
            )
            Text (
                text = uploadState.serverCount,
                fontSize = 20.sp
            )
        }
        FilledTonalButton(onClick = {
            uploadViewModel.reloadCount()
        },
            modifier = Modifier
                .size(100.dp, 70.dp)
                .align(Alignment.End)
        ) {
            Text(text = "Reload Counts",)
        }
        uploadState.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ServerUploadPreview() {
    ServerUploadScreen()
}