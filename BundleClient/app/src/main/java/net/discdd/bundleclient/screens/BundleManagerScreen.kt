package net.discdd.bundleclient.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.viewmodels.BundleManagerViewModel

@Composable
fun ManagerScreen(
    bundleViewModel: BundleManagerViewModel = viewModel(),
) {
    val managerState by bundleViewModel.state.collectAsState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column (
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "\n  k9:",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "bundleUp: ",
                    fontSize = 20.sp
                )
                Text(
                    text = managerState.numberBundlesSent,
                    fontSize = 20.sp
                )
                Text(
                    text = "        bundleDown: ",
                    fontSize = 20.sp
                )
                Text(
                    text = managerState.numberBundlesReceived,
                    fontSize = 20.sp
                )
            }
            FilledTonalButton(
                onClick = { bundleViewModel.refresh() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RELOAD")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BundleManagerScreenPreview() {
    ManagerScreen()
}