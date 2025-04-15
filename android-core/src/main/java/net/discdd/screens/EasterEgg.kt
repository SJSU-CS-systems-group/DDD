package net.discdd.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun EasterEgg(
    content: @Composable () -> Unit,
    onToggle: () -> Unit
) {
    var clickTimes by remember { mutableStateOf<List<Long>>(emptyList()) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null // conceals ripple effect
            ) {
                val now = System.currentTimeMillis()
                clickTimes = (clickTimes + now)
                    .filter { now - it <= 3000 }
                    .takeLast(7)
                if (clickTimes.size == 7) {
                    val timeDiff = clickTimes.last() - clickTimes.first()
                    if (timeDiff <= 3000) {
                        onToggle()
                        clickTimes = emptyList()
                    }
                }
            }
    ) {
        content()
    }
}
