package net.discdd.screens

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
import androidx.compose.ui.unit.dp
import net.discdd.components.UsbFileRequestUI
import net.discdd.viewmodels.UsbViewModel

@Composable
fun <T : UsbViewModel> UsbScreen(
        usbViewModel: T,
        usbExchangeComponent: @Composable (T) -> Unit
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
                usbExchangeComponent(usbViewModel)
            } else {
                UsbFileRequestUI(usbViewModel)
            }
        }
    }
}