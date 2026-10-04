package net.discdd.bundledropbox.screens

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.bundledropbox.DropboxServiceManager
import net.discdd.bundledropbox.DropboxStatusRepository
import net.discdd.bundledropbox.R
import net.discdd.components.NotificationBottomSheet
import net.discdd.screens.BugReportScreen
import net.discdd.screens.LogScreen
import net.discdd.screens.PermissionScreen
import net.discdd.viewmodels.SettingsViewModel
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("DropboxHomeScreen")

/** One pager tab: title plus content (same shape as the transport's `TabItem`). */
data class TabItem(
        val title: String,
        val screen: @Composable () -> Unit,
)

/**
 * Root composable of DDD Dropbox, mirroring BundleTransport's `TransportHomeScreen`: a title row, a
 * [ScrollableTabRow] and a [HorizontalPager].
 *
 * Standard tabs: Status, Local Wifi, Storage, Settings, Bug reports. Admin tabs (Logs, Permissions)
 * appear when android-core's easter egg is toggled on the Status tab. As in the transport,
 * android-core's [SettingsViewModel] drives the first-open notification sheet and the easter egg.
 *
 * Runtime permissions: NEARBY_WIFI_DEVICES (every Wi-Fi P2P call, both roles; declared
 * `neverForLocation`, so no location permission is needed, exactly like the stock BundleClient) and
 * POST_NOTIFICATIONS (foreground-service notification, asked through [NotificationBottomSheet] on first
 * open). When the Wi-Fi permission becomes granted the service is told to retry radio work.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DropboxHomeScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstOpen by viewModel.firstOpen.collectAsState()
    val showEasterEgg by viewModel.showEasterEgg.collectAsState()
    val status by DropboxStatusRepository.status.collectAsState()
    val connected by DropboxServiceManager.connected.collectAsState()

    val nearbyWifiState = rememberPermissionState(Manifest.permission.NEARBY_WIFI_DEVICES)
    val notificationState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS,
            onPermissionResult = { viewModel.onFirstOpen() })

    // Same hook as the transport (TransportHomeScreen -> wifiPermissionGranted), but off the main thread
    // because DropboxControls methods may block briefly. Unlike the transport's hook, ours can throw
    // (the service reports "failed to start" / "still starting" through IllegalStateException), and an
    // uncaught exception in a LaunchedEffect would crash the app, so it is caught and logged; the service
    // already publishes the failure as lastError on the Status tab. Keyed on `connected` too: a grant
    // that lands before the service is bound would otherwise be lost (controls() is null), leaving the
    // radio in UNAVAILABLE until its 30 s retry (audit i6).
    LaunchedEffect(nearbyWifiState.status, connected) {
        if (connected && nearbyWifiState.status.isGranted) {
            withContext(Dispatchers.IO) {
                runCatching { DropboxServiceManager.controls()?.wifiPermissionGranted() }
                        .onFailure { logger.log(Level.WARNING, "wifiPermissionGranted failed: ${it.message}") }
            }
        }
    }

    val standardTabs = remember {
        listOf(
                TabItem(title = context.getString(R.string.status)) {
                    StatusScreen(onToggleEasterEgg = {
                        viewModel.onToggleEasterEgg()
                        Toast.makeText(context, "Easter Egg Toggled!", Toast.LENGTH_SHORT).show()
                    })
                },
                TabItem(title = context.getString(R.string.local_wifi)) {
                    WifiDirectScreen(permissionStates = listOf(nearbyWifiState))
                },
                TabItem(title = context.getString(R.string.storage)) { StorageScreen() },
                TabItem(title = context.getString(R.string.settings)) { SettingsScreen() },
                TabItem(title = context.getString(R.string.bug_reports)) { BugReportScreen() },
        )
    }

    // adminTabs are developer-only features, revealed by the Easter Egg on the Status tab.
    val adminTabs = remember {
        listOf(
                TabItem(title = context.getString(R.string.logs)) { LogScreen() },
                TabItem(title = context.getString(R.string.permissions)) {
                    PermissionScreen(runtimePermissions = listOf(nearbyWifiState, notificationState))
                },
        )
    }

    var tabItems by remember { mutableStateOf(standardTabs) }
    LaunchedEffect(showEasterEgg) {
        tabItems = if (showEasterEgg) standardTabs + adminTabs else standardTabs
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val pagerState = rememberPagerState { tabItems.size }
        val selectedTabIndex by remember { derivedStateOf { pagerState.currentPage } }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = context.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                // Where the transport shows "Custom Server", the dropbox shows which role it is in.
                Text(
                        text = context.getString(when {
                            status.mode().isHostSide() -> R.string.title_role_host
                            status.mode().isVisitSide() -> R.string.title_role_visit
                            else -> R.string.title_role_idle
                        }),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
            }

            ScrollableTabRow(selectedTabIndex = selectedTabIndex.coerceIn(tabItems.indices), edgePadding = 0.dp) {
                tabItems.forEachIndexed { index, item ->
                    Tab(
                            selected = index == selectedTabIndex,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(text = item.title) }
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { index ->
                tabItems[index].screen()
            }
        }

        if (firstOpen) {
            NotificationBottomSheet(notificationState)
        }
    }
}
