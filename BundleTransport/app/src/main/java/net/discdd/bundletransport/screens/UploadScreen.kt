package net.discdd.bundletransport.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.apps.card.v1.Columns.Column
import net.discdd.bundletransport.viewmodels.UploadViewModel
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.sp



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
                    UploadScreen()
                }
            }
        }
    }
}

@Composable
fun UploadScreen(
    uploadViewModel: UploadViewModel = viewModel(),
) {
    val uploadState by uploadViewModel.state.collectAsState()
    Column (
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(onClick = {uploadViewModel.connectServer()},
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
        uploadState.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
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
                text = "   toServer: ",
                fontSize = 20.sp
            )
            Text (
                text = uploadState.serverCount,
                fontSize = 20.sp
            )
            Text (text = "   ")
            FilledTonalButton(onClick = {
                uploadViewModel.reloadCount()
            },
                modifier = Modifier.size(170.dp, 60.dp)) {
                Text(
                    text = "Reload Counts",
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UploadPreview() {
    UploadScreen()
}