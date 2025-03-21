package net.discdd.screens

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.viewmodels.PermissionItemData
import net.discdd.viewmodels.PermissionsViewModel

@Composable
fun PermissionScreen(
    viewModel: PermissionsViewModel = viewModel(),
    activityResultLauncher: ActivityResultLauncher<Array<String>>?
) {
    val permissionItems by viewModel.permissionItems.collectAsState()
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(permissionItems) { itemData ->
                PermissionItem(
                    permissionItem = itemData,
                    onClick = {
                        if (!itemData.isBoxChecked) viewModel.triggerPermissionDialog(context)
                    },
                    onCheckPermission = {
                        viewModel.checkPermission(itemData.permissionName)
                        val remainingPermissions = viewModel.getPermissionsToRequest()
                        if (remainingPermissions.isNotEmpty()) {
                            activityResultLauncher?.launch(remainingPermissions)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionItem(
    permissionItem: PermissionItemData,
    onClick: () -> Unit,
    onCheckPermission: () -> Unit
) {
    val context = LocalContext.current
    val resId = context.resources.getIdentifier(
        permissionItem.permissionName,
        "string",
        context.packageName
    )
    val permissionText = if (resId == 0) permissionItem.permissionName else stringResource(id = resId)

    LaunchedEffect(permissionItem.permissionName) {
        onCheckPermission()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = permissionItem.isBoxChecked,
            onCheckedChange = null
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = permissionText,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionItemPreview() {
    PermissionItem(PermissionItemData(true, "previewTest"), {}, {})
}

@Preview(showBackground = true)
@Composable
fun PermissionScreenPreview() {
    PermissionScreen(activityResultLauncher = null)
}