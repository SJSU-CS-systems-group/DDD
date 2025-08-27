package net.discdd.bundletransport.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.ConnectivityManager
import net.discdd.components.NotificationBottomSheet
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportServiceManager
import net.discdd.bundletransport.viewmodels.TransportUsbViewModel
import net.discdd.screens.LogScreen
import net.discdd.screens.PermissionScreen
import net.discdd.screens.UsbScreen
import net.discdd.screens.BugReportScreen
import net.discdd.viewmodels.SettingsViewModel

data class TabItem(
        val title: String,
        val screen: @Composable () -> Unit,
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TransportHomeScreen(
        viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstOpen by viewModel.firstOpen.collectAsState()
    val showUsbScreen by UsbConnectionManager.usbConnected.collectAsState()
    val showEasterEgg by viewModel.showEasterEgg.collectAsState()
    val internetAvailable by ConnectivityManager.internetAvailable.collectAsState()
    val nearbyWifiState = rememberPermissionState(
            Manifest.permission.NEARBY_WIFI_DEVICES
    )
    val locationPermissionState = rememberPermissionState(
            Manifest.permission.ACCESS_FINE_LOCATION,
      )
    LaunchedEffect(nearbyWifiState.status) {
        if (nearbyWifiState.status.isGranted && locationPermissionState.status.isGranted) {
            TransportServiceManager.getService()?.wifiPermissionGranted()
        }
    }

    val notificationState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS,
            onPermissionResult = {
                viewModel.onFirstOpen()
            }
    )

    val standardTabs = remember {
        listOf(
                TabItem(
                        title = context.getString(R.string.upload),
                        screen = {
                            ServerUploadScreen(settingsViewModel = viewModel) {
                                viewModel.onToggleEasterEgg()
                                Toast.makeText(context, "Easter Egg Toggled!", Toast.LENGTH_SHORT).show()
                            }
                        }
                ),
                TabItem(
                        title = context.getString(R.string.local_wifi),
                        screen = {
                            WifiDirectScreen(
                                serviceReadyFuture = TransportServiceManager.serviceReady,
                                nearbyWifiState = nearbyWifiState,
                                locationPermissionState = locationPermissionState
                            )
                        }
                ),
                TabItem(
                        title = context.getString(R.string.storage),
                        screen = {
                            StorageScreen()
                        }
                ),
                TabItem(
                        title = "App Share",
                        screen = { AppShareScreen() }
                ),
                TabItem(
                        title = "Bug reports",
                        screen = { BugReportScreen() }
                ),
        )
    }

    /*
    * adminTabs are features that should only be shown to developers
    * these features can be toggled by interacting with the Easter Egg
    */
    val adminTabs = listOf(
            TabItem(
                    title = context.getString(R.string.logs),
                    screen = { LogScreen() }
            ),
            TabItem(
                    title = context.getString(R.string.permissions),
                    screen = { PermissionScreen(runtimePermissions = listOf(nearbyWifiState, notificationState)) }
            ),
    )

    val usbTab = TabItem(
            title = stringResource(R.string.usb),
            screen = {
                val usbViewModel: TransportUsbViewModel = viewModel()
                UsbScreen(usbViewModel) { viewModel ->
                    TransportUsbComponent(viewModel) {
                        viewModel.transportTransferToUsb(context)
                        viewModel.usbTransferToTransport(context)
                        viewModel.setShouldEject(true)
                    }
                }
            }
    )

    var tabItems by remember {
        mutableStateOf(standardTabs)
    }

    LaunchedEffect(internetAvailable, showUsbScreen, showEasterEgg) {
        var newTabs = standardTabs.toMutableList()
        if (showUsbScreen) newTabs += usbTab
        if (showEasterEgg) newTabs += adminTabs
        tabItems = newTabs
    }

    Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
    ) {
        val pagerState = rememberPagerState() { tabItems.size }
        val selectedTabIndex by remember {
            derivedStateOf { pagerState.currentPage }
        }
        Column(
                modifier = Modifier.fillMaxSize()
        ) {
            Row(
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                        text = context.getString(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                )
            }

            ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex.coerceIn(tabItems.indices),
                    edgePadding = 0.dp
            ) {
                tabItems.forEachIndexed { index, item ->
                    Tab(
                            selected = index == selectedTabIndex,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = {
                                Text(text = item.title)
                            }
                    )
                }
            }

            HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
            ) { index ->
                tabItems[index].screen()
            }
        }

        if (firstOpen) {
            NotificationBottomSheet(notificationState)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransportHomeScreenPreview() {
    TransportHomeScreen()
}
