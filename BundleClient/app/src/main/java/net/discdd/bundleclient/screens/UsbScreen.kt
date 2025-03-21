package net.discdd.bundleclient.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.R
import net.discdd.bundleclient.UsbConnectionManager
import net.discdd.bundleclient.viewmodels.UsbState
import net.discdd.bundleclient.viewmodels.UsbViewModel

@Composable
fun UsbScreen(
    usbViewModel: UsbViewModel = viewModel()
) {
    val usbState by usbViewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
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
    usbViewModel: UsbViewModel,
    usbState: UsbState
) {
    val isUsbConnected by UsbConnectionManager.usbConnected.collectAsState()

    Text(
        text = when {
            isUsbConnected && usbState.dddDirectoryExists ->
                stringResource(R.string.usb_connection_detected)
            isUsbConnected && !usbState.dddDirectoryExists ->
                stringResource(R.string.usb_was_connected_but_ddd_transport_directory_was_not_detected)
            else ->
                stringResource(R.string.no_usb_connection_detected)
        },
        color = when {
            isUsbConnected && usbState.dddDirectoryExists -> Color.Green
            else -> Color.Red
        },
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Exchange button
    Button(
        onClick = { usbViewModel.transferBundleToUsb() },
        enabled = isUsbConnected && usbState.dddDirectoryExists,
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = stringResource(R.string.exchange_usb_data)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = {
            usbViewModel.checkDddDirExists()
        },
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = stringResource(R.string.reload)
        )
    }

    // show messgae
    usbState.showMessage?.let { msg ->
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = msg,
            color = Color(usbState.messageColor),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun PermissionRequestUI(
    usbViewModel: UsbViewModel
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.to_settings_text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    )

    Button(
        onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(context, intent, null)
        },
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = stringResource(R.string.to_settings_button)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = { usbViewModel.refreshFilePermission() },
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = stringResource(R.string.reload)
        )
    }
}
