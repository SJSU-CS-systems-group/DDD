package net.discdd.bundletransport.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundletransport.viewmodels.StorageViewModel
import net.discdd.theme.ComposableTheme

class StorageFragment: Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposableTheme {
                    StorageScreen()
                }
            }
        }
    }
}

@Composable
fun StorageScreen(
    viewModel: StorageViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val sliderRange = 0f..maxOf(1000f, state.totalBytes.toFloat())
    val maxSliderValue = minOf(state.totalBytes.toFloat(), state.sliderValue.toFloat())

    LaunchedEffect(state.showMessage) {
        if (state.showMessage != null) {
            delay(2000)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Slider(
            value = maxOf(0f, minOf(maxSliderValue, sliderRange.endInclusive)),
            onValueChange = { viewModel.onSliderValueChange(it.toLong()) },
            valueRange = 0f..state.totalBytes.toFloat(),
        )
        Text("Storage: ${state.actualStorageValue} MB")
        Button(
            onClick = { viewModel.onSetStorageClick() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set Storage")
        }
        Text("Free Space: ${state.freeSpace} MB")
        Text("Used Space: ${state.usedSpace} MB")
        state.showMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StoragePreview() {
    StorageScreen()
}