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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.R
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
            for (app in managerState.appData) {
            Text(
                text = app.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
                Text(
                    text = "    out: delivered ${app.ackedOut} pending ${app.lastOut - app.ackedOut}",
                    fontSize = 20.sp
                )
                Text(
                    text = "    in: processed ${app.ackedIn} pending ${app.lastIn - app.ackedIn}",
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