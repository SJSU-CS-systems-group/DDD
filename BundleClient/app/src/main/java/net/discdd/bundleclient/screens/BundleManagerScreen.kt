package net.discdd.bundleclient.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import net.discdd.bundleclient.viewmodels.BundleManagerViewModel
import net.discdd.theme.ComposableTheme

class BundleManagerFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposableTheme {
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
        Text(
            text = "\n\n  k9:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Row (
            modifier = Modifier
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "bundleUp:  ",
                fontSize = 20.sp
            )
            Text(
                text = managerState.numberBundlesSent,
                fontSize = 20.sp
            )
            Text(
                text = "        bundleDown:  ",
                fontSize = 20.sp
            )
            Text(
                text = managerState.numberBundlesReceived,
                fontSize = 20.sp
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