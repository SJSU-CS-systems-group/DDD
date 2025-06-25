package net.discdd.components

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat.startActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.discdd.android_core.R
import net.discdd.viewmodels.UsbViewModel

@Composable
fun UsbFileRequestUI(
        usbViewModel: UsbViewModel
) {
    val context = LocalContext.current

    Text(
            text = stringResource(R.string.to_settings_text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
    )

    Button(
            onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(context, intent, null)
            },
            modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
                text = stringResource(R.string.to_settings),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    /**
     * reword reload to be more specific. select USB dir?
     */
    Button(
            onClick = { usbViewModel.promptForDirectoryAccess() },
            modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
                text = stringResource(R.string.reload)
        )
    }

    /**
     * add similar button to one above. select APK source dir?
     */
}

@Preview(showBackground = true)
@Composable
fun UsbFileRequestUIPreview() {
    val viewModel = UsbViewModel(Application())
    UsbFileRequestUI(viewModel)
}