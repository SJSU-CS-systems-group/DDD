package net.discdd.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Level.INFO
import java.util.logging.Level.WARNING
import java.util.logging.Logger

class BugReportViewModel : ViewModel() {

    private val logger = Logger.getLogger(BugReportViewModel::class.java.getName())

    private val _bugReportText = MutableStateFlow("")
    val bugReportText: StateFlow<String> = _bugReportText.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitResult = MutableStateFlow<String?>(null)
    val submitResult: StateFlow<String?> = _submitResult.asStateFlow()

    fun updateBugReportText(text: String) {
        _bugReportText.value = text
    }

    /**
     * With a [sink] the report is handed to it (BundleClient sends it to the server); without one it is only
     * appended to a local file on the device.
     */
    fun submitBugReport(context: Context, sink: BugReportSink? = null) {
        // will prevent memory leaks
        viewModelScope.launch {
            _isSubmitting.value = true
            _submitResult.value = null

            try {
                val report = formatReport(_bugReportText.value)
                val success = withContext(Dispatchers.IO) {
                    sink?.submit(context.applicationContext, report) ?: saveBugReportToFile(context, report)
                }
                if (success) {
                    _submitResult.value = if (sink != null) {
                        "Bug report queued. It will be sent to the server with your next bundle exchange."
                    } else {
                        "Bug report saved on this device."
                    }
                    _bugReportText.value = "" // clears the text field
                } else {
                    _submitResult.value = "Failed to save bug report"
                }
            } catch (e: Exception) {
                logger.log(WARNING, "Error saving bug report", e)
                _submitResult.value = "Error: ${e.message}"
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    private fun formatReport(bugReport: String): String {
        // fetch most current logs
        val logMessages = LogViewModel.getCurrLogs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return "=== Bug Report - $timestamp ===\n$bugReport\n\n=== System Logs ===\n${logMessages.joinToString("\n")}\n\n"
    }

    private fun saveBugReportToFile(context: Context, reportContent: String): Boolean {
        return try {
            // adapted from crash report logic
            val rootDir: Path = context.applicationContext.dataDir.toPath()
            logger.log(INFO, "root directory for bug report: $rootDir")

            val clientDestDir: Path = rootDir.resolve("to-be-bundled")
            val finalRootDir = if (clientDestDir.toFile().exists()) {
                logger.log(INFO, "Writing bug report to client device internal storage")
                clientDestDir
            } else {
                var dir = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
                val externalDir = dir.toPath()
                        ?: throw IllegalStateException("External files directory is null")
                logger.log(INFO, "Writing bug report to transport device external storage")
                externalDir
            }

            val logFile = File(finalRootDir.toString(), "bug_report.txt")

            // appends to file (or creates if doesn't exist)
            FileWriter(logFile, true).use { writer ->
                writer.write(reportContent)
            }

            logger.log(INFO, "Bug report saved to: ${logFile.absolutePath}")
            true
        } catch (e: Exception) {
            logger.log(INFO, "Failed to save bug report")
            false
        }
    }

    fun clearSubmitResult() {
        _submitResult.value = null
    }
}