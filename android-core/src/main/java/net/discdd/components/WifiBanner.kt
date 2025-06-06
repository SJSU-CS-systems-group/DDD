package net.discdd.components

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import net.discdd.android_core.R

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiPermissionBanner(
        numDenied: Int,
        nearbyWifiState: PermissionState,
        modifier: Modifier = Modifier,
        backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
        contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        onEnableClick: () -> Unit,
) {
    val textToShow = if (nearbyWifiState.status.shouldShowRationale) {
        stringResource(R.string.urgent_ask_permission)
    } else if (numDenied < 2) {
        stringResource(R.string.generic_ask_permission)
    } else {
        stringResource(R.string.manual_action_required)
    }

    Surface(
            color = backgroundColor,
            modifier = modifier.fillMaxWidth()
    ) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
        ) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Wifi Disabled",
                        tint = contentColor
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                        text = "Enable WiFi Device Connection",
                        color = contentColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                    text = textToShow,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 36.dp)
            )

            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                        onClick = onEnableClick,
                        colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                        )
                ) {
                    Text(
                            text = "Enable",
                            fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Preview(showBackground = true)
@Composable
fun WifiPermissionBannerPreview() {
    val fooState = rememberPermissionState(Manifest.permission.NEARBY_WIFI_DEVICES)
    WifiPermissionBanner(-1, fooState) {}
}