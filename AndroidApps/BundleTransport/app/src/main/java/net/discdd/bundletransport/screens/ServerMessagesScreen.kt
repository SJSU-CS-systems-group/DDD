package net.discdd.bundletransport.screens

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import net.discdd.bundletransport.utils.ServerMessage
import net.discdd.bundletransport.utils.rememberNoFlingSwipeToDismissBoxState
import net.discdd.bundletransport.viewmodels.ServerMessagesViewModel

@Composable
fun ServerMessagesScreen(
    viewModel: ServerMessagesViewModel = viewModel(),
    onRefresh: () -> Unit = { }
) {
    val notifs by viewModel.messages.observeAsState(emptyList())
    val unreadCount = notifs.count {!it.read}
    var dialogFor by remember { mutableStateOf<ServerMessage?>(null) }

        if(notifs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notifications yet.")
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                //Header (# unread notifs and refresh button)
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
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = { onRefresh() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    }
                }
                items(notifs, key = { it.messageId }) { notif ->
                    val dismissState = rememberNoFlingSwipeToDismissBoxState(
                        positionalThreshold = { it * 0.55f },//deleting feels weird, will prob change
                        confirmValueChange = {
                            val swiped = it == SwipeToDismissBoxValue.StartToEnd
                                      || it == SwipeToDismissBoxValue.EndToStart
                            if(swiped) {
                                viewModel.deleteById(notif.messageId)
                                true
                            }
                            else false
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
                                notif = notif,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                onCardClick = {
                                    viewModel.markRead(notif.messageId)
                                    dialogFor = notif
                                }
                            )
                        }
                    )
                }
            }
        }

    //Popup when notif is clicked
    dialogFor?.let { notif ->
        val ctx = LocalContext.current
        val whenText = notif.date
        Dialog(onDismissRequest = { dialogFor = null }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Notification", style = MaterialTheme.typography.titleLarge)
                    Text(dialogFor!!.message, style = MaterialTheme.typography.bodyLarge)

                    Text(
                        "Sent: $whenText",
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
            if (!notif.read) MaterialTheme.colorScheme.secondaryContainer
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
                Text(
                    text = notif.message,
                   modifier = Modifier
                        .weight(15f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = notif.date
                )
            }
        }
    }
}

//Update time text on notif card ("_m ago")
@Composable
private fun RelativeTimeText(sentAtMillis: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentAtMillis) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val msToNextMinute = 60_000 - (nowMs % 60_000)
            delay(msToNextMinute.coerceIn(500L, 60_000L))
            now = System.currentTimeMillis()
        }
    }

    val mins = ((now - sentAtMillis) / 60_000L).coerceAtLeast(0)
    Text("$mins m ago", style = MaterialTheme.typography.labelMedium)
}