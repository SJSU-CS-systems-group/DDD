package net.discdd.bundletransport.screens

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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.ConnectivityManager
import net.discdd.components.NotificationBottomSheet
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportWifiServiceManager
import net.discdd.bundletransport.viewmodels.TransportUsbViewModel
import net.discdd.screens.LogScreen
import net.discdd.screens.PermissionScreen
import net.discdd.screens.UsbScreen
import net.discdd.viewmodels.SettingsViewModel

data class TabItem(
    val title: String,
    val screen: @Composable () -> Unit,
)

@Composable
fun TransportHomeScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstOpen by viewModel.firstOpen.collectAsState()
    val showUsbScreen by UsbConnectionManager.usbConnected.collectAsState()

    val standardTabs = remember {
        listOf(
            TabItem(
                title = context.getString(R.string.upload),
                screen = {
                    ServerUploadScreen()
                }
            ),
            TabItem(
                title = context.getString(R.string.local_wifi),
                screen = {
                    WifiDirectScreen(
                        serviceReadyFuture = TransportWifiServiceManager.serviceReady
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
                title = context.getString(R.string.logs),
                screen = {
                    LogScreen()
                }
            ),
            TabItem(
                title = context.getString(R.string.permissions),
                screen = {
                    PermissionScreen()
                }
            )
        )
    }

    val usbTab = listOf(
        TabItem(
            title = stringResource(R.string.usb),
            screen = {
                val usbViewModel: TransportUsbViewModel = viewModel()
                UsbScreen(
                    usbViewModel = usbViewModel
                ) { viewModel ->
                    TransportUsbComponent(viewModel) {
                        viewModel.populate()
                    }
                }
            }
        )
    )

    var tabItems by remember {
        mutableStateOf(standardTabs)
    }

    LaunchedEffect(showUsbScreen) {
        tabItems = if (showUsbScreen) {
            usbTab + standardTabs
        } else {
            standardTabs
        }
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
                    text = "foo app",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
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
            NotificationBottomSheet(viewModel)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransportHomeScreenPreview() {
    TransportHomeScreen()
}
