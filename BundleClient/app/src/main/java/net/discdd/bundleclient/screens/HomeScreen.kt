package net.discdd.bundleclient.screens

import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.discdd.bundleclient.R
import net.discdd.bundleclient.UsbConnectionManager
import net.discdd.bundleclient.WifiAwareManager
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.screens.LogScreen
import net.discdd.screens.PermissionScreen
import net.discdd.viewmodels.PermissionsViewModel

data class TabItem(
    val title: String,
    val screen: @Composable () -> Unit,
)

@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    activityResultLauncher: ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showUsbScreen by UsbConnectionManager.usbConnected.collectAsState()
    val standardTabs = remember {
        listOf(
            TabItem(
                title = context.getString(R.string.home_tab),
                screen = { WifiDirectScreen(serviceReadyFuture = WifiServiceManager.serviceReady) }
                ),
            TabItem(
                title = "Wifi Aware",
                screen = { WifiAwareSubscriberScreen(serviceReadyFuture = WifiAwareManager.serviceReady) }
            ),
            TabItem(
                title = context.getString(R.string.server_tab),
                screen = { ServerScreen() }
            ),
            TabItem(
                title = context.getString(R.string.logs_tab),
                screen = { LogScreen() }
            ),
            TabItem(
                title = context.getString(R.string.bm_tab),
                screen = { ManagerScreen() }
            ),
            TabItem(
                title = context.getString(R.string.permissions_tab),
                screen = { PermissionScreen(permissionsViewModel, activityResultLauncher) }
            )
        )
    }
    val usbTab = listOf(TabItem(
        title = context.getString(R.string.usb_tab),
        screen = { UsbScreen() }
    ))

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
                    text = context.getString(R.string.app_name),
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
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
//    HomeScreen()
}
