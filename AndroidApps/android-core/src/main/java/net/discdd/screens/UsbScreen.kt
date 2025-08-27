package net.discdd.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
            if (usbState.shouldEject) {
                val context = LocalContext.current
                fun ejectUsb() {
                    usbViewModel.setShouldEject(false)
                    val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    context.startActivity(intent)
                }

                AlertDialog(
                        onDismissRequest = {
                            ejectUsb()
                        },
                        confirmButton = {
                            TextButton(
                                    onClick = {
                                        ejectUsb()
                                    }
                            ) {
                                Text("I understand")
                            }
                        },
                        text = {
                            Text(
                                    "On the next screen, locate the USB storage -> click on three dots to right of storage -> select unmount or eject"
                            )
                        },
                )
            }
            usbExchangeComponent(usbViewModel)
        }
    }
}