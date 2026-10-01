package net.discdd.bundledropbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.discdd.theme.ComposableTheme

/**
 * Launcher activity of DDD Dropbox.
 *
 * Placeholder from the scaffold milestone: it only renders a title so the module builds. The UI agent
 * replaces the content with `DropboxHomeScreen` (tabs Status, Local Wifi, Storage, Settings,
 * Bug reports; admin Logs and Permissions), and the integrator starts and binds
 * [net.discdd.bundledropbox.service.DropboxService] here, mirroring `BundleTransportActivity`.
 * Unlike the transport, the dropbox must never stop its service in onDestroy: it is a stationary relay.
 */
class BundleDropboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposableTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
