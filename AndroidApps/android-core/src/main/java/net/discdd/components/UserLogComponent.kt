package net.discdd.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.collectLatest
import net.discdd.utils.UserLogRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.Level

val DATE_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())


@Composable
fun UserLogComponent(type: UserLogRepository.UserLogType) {
    val trigger = remember { mutableIntStateOf(0) }

    LaunchedEffect(UserLogRepository.event) {
        UserLogRepository.event.collectLatest {
            trigger.intValue++
        }
    }

    val entries = remember(trigger.intValue) {
        UserLogRepository.getRepo(type)
    }
    ScrollingColumn(showActionButton = entries.isNotEmpty(), onActionClick = {UserLogRepository.clearRepo(type)}) {
        for (entry in entries.asReversed()) {
            // format entry.time from milliseconds since epoch to HH:MM:SS
            val timeStr = DATE_FORMAT.format(Date(entry.time))
            Text(
                text = "$timeStr ${entry.message}",
                color = when (entry.level) {
                    Level.SEVERE -> Color.Red
                    Level.WARNING -> Color.Yellow
                    else -> Color.Blue
                }
            )
        }
    }
}