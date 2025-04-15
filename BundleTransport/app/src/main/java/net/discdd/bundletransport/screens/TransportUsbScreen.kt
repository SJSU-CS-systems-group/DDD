package net.discdd.bundletransport.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundletransport.viewmodels.TransportUsbState
import net.discdd.bundletransport.viewmodels.TransportUsbViewModel
import net.discdd.pathutils.TransportPaths

@Composable
fun TransportUsbScreen(
    usbViewModel: TransportUsbViewModel = viewModel(),
    transportPaths: TransportPaths,
) {
    val usbState by usbViewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (usbState.filePermissionGranted) {
                UsbFeatureUI(usbViewModel, usbState)
            } else {
                PermissionRequestUI(usbViewModel)
            }
        }
    }
}

@Composable
fun UsbFeatureUI(
   usbViewModel: TransportUsbViewModel,
   usbState: TransportUsbState,
) {
    Surface() {}
}

@Composable
fun PermissionRequestUI(
    usbViewModel: TransportUsbViewModel,
) {
    Surface() {}
}

