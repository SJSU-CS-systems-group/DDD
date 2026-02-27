package net.discdd.bundleclient.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.material3.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import net.discdd.bundleclient.utils.ServerMessage
import net.discdd.bundleclient.utils.rememberNoFlingSwipeToDismissBoxState
import net.discdd.bundleclient.viewmodels.ServerMessagesViewModel
import java.time.format.DateTimeFormatter

@Composable
fun ServerMessagesScreen(
        viewModel: ServerMessagesViewModel = viewModel(),
) {
    val notifications by viewModel.messages.observeAsState(emptyList())
    val unreadCount = notifications.count { !it.isRead }
    var dialogFor by remember { mutableStateOf<ServerMessage?>(null) }

    if (notifications.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No notifications yet.")
        }
    } else {
        LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
        ) {
            //Header (# unread notifs)
            item(key = "header") {
                Row(
                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "New Notifications: ${unreadCount}",
                            fontSize = 20.sp
                    )
                }
            }
            items(notifications, key = { it.messageId }) { notification ->
                val dismissState = rememberNoFlingSwipeToDismissBoxState(
                        positionalThreshold = { it * 0.55f }, //change 0.55f to change threshold
                        confirmValueChange = {
                            val swiped = it == SwipeToDismissBoxValue.StartToEnd
                                    || it == SwipeToDismissBoxValue.EndToStart
                            if (swiped) {
                                viewModel.deleteById(notification.messageId)
                                true
                            } else false
                        }
                )

                SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = true,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            val shape = MaterialTheme.shapes.medium
                            Box(
                                    modifier = Modifier.fillMaxSize()
                                            .padding(horizontal = 16.dp)
                                            .clip(shape)
                                            .background(MaterialTheme.colorScheme.errorContainer),
                                    contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                                .padding(24.dp)
                                                .requiredSize(24.dp)
                                )
                                Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.align(Alignment.CenterEnd)
                                                .padding(24.dp)
                                                .requiredSize(24.dp)
                                )
                            }
                        },
                        content = {
                            //Notif card
                            NotifCard(
                                    notif = notification,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    onCardClick = {
                                        viewModel.markRead(notification.messageId)
                                        dialogFor = notification
                                    }
                            )
                        }
                )
            }
        }
    }

    //Popup when notif is clicked
    dialogFor?.let { notif ->
        Dialog(onDismissRequest = { dialogFor = null }) {
            Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Notification", style = MaterialTheme.typography.titleLarge)
                    Text(notif.message ?: "", style = MaterialTheme.typography.bodyLarge)
                    Text(
                            "Sent: ${notif.date?.toString() ?: "Unknown date"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { dialogFor = null }) { Text("OK") }
                    }
                }
            }
        }
    }
}

//UI for each notification card
@Composable
private fun NotifCard(
        notif: ServerMessage,
        modifier: Modifier = Modifier,
        onCardClick: () -> Unit,
) {
    val container by animateColorAsState(
            targetValue =
            if (!notif.isRead) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
    )
    Card(
            modifier = modifier,
            onClick = onCardClick,
            colors = CardDefaults.cardColors(
                    containerColor = container
            )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!notif.isRead) {
                    Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(8.dp)
                    ) {}
                }
                Text(
                        text = notif.message ?: "",
                        modifier = Modifier
                                .weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                        text = notif.date?.format(DateTimeFormatter.ofPattern("M/d h:mm a")) ?: ""
                )
            }
        }
    }
}
