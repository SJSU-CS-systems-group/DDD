package net.discdd.bundleclient.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import net.discdd.bundleclient.viewmodels.BundleManagerViewModel

class BundleManagerFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    ManagerScreen()
                }
            }
        }
    }
}

@Composable
fun ManagerScreen(
    bundleViewModel: BundleManagerViewModel = viewModel(),
) {
    val managerState by bundleViewModel.state.collectAsState()
    Column (
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "k9:")
        Row (
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "bundleUp:  ",
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = managerState.numberBundlesSent,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.width(20.dp))
            Text(
                text = "bundleDown:  ",
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = managerState.numberBundlesReceived,
                modifier = Modifier.fillMaxWidth()
            )
        }
        FilledTonalButton(onClick = {bundleViewModel.refresh()},
            modifier = Modifier.fillMaxWidth()) {
            Text("RELOAD")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BundleManagerScreenPreview() {
    ManagerScreen()
}