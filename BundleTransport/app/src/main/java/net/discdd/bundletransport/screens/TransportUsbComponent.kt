package net.discdd.bundletransport.screens

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.R
import net.discdd.bundletransport.viewmodels.TransportUsbViewModel

@Composable
fun TransportUsbComponent(
        usbViewModel: TransportUsbViewModel,
        onTransferClick: () -> Unit,
) {
    val usbState by usbViewModel.state.collectAsState()
    val isUsbConnected by UsbConnectionManager.usbConnected.collectAsState()

    val textToShow = when {
        isUsbConnected && usbState.dddDirectoryExists ->
            stringResource(R.string.usb_connection_detected)

        isUsbConnected && !usbState.dddDirectoryExists ->
            stringResource(R.string.usb_was_connected_but_ddd_transport_directory_was_not_detected)

        else ->
            stringResource(R.string.no_usb_connection_detected)
    }

    val textColor = when {
        isUsbConnected && usbState.dddDirectoryExists -> Color.Green
        else -> Color.Red
    }

    Text(
            text = textToShow,
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
            onClick = onTransferClick,
            enabled = isUsbConnected && usbState.dddDirectoryExists,
            modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
                text = stringResource(R.string.exchange_usb_data)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
            onClick = { usbViewModel.checkDddDirExists() },
            modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
                text = stringResource(R.string.reload)
        )
    }

    // show message
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

@Preview(showBackground = true)
@Composable
fun TransportUsbComponentPreview() {
    val viewModel = TransportUsbViewModel(Application())
    TransportUsbComponent(viewModel) {}
}