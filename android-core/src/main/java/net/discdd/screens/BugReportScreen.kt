package net.discdd.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.viewmodels.BugReportViewModel

@Composable
fun BugReportScreen(
    viewModel: BugReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val bugReportText by viewModel.bugReportText.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val submitResult by viewModel.submitResult.collectAsState()

    LaunchedEffect(submitResult) {
        if (submitResult != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearSubmitResult()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Please describe the bug you encountered. \nYou can describe what happened, what you expected, and/or steps to reproduce.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = bugReportText,
            onValueChange = viewModel::updateBugReportText,
            label = { Text("Bug Description") },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            enabled = !isSubmitting
        )
        Button(
            onClick = { viewModel.submitBugReport(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isSubmitting && bugReportText.isNotBlank()
        ) {
            if (isSubmitting) {
                Text("Submitting...")
            } else {
                Text("Submit Bug Report")
            }
        }
        submitResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}