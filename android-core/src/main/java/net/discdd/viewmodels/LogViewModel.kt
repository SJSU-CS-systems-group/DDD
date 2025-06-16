package net.discdd.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

data class LogState(
        val logMessages: List<String> = emptyList()
)

class LogViewModel : ViewModel() {
    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    init {
        refreshLogMsgs()
    }

    fun refreshLogMsgs() {
        viewModelScope.launch {
            val reversedLogRecords = LinkedList(logRecords).apply { reverse() }
            _state.update { currentState ->
                currentState.copy(logMessages = reversedLogRecords)
            }
        }
    }

    companion object {
        private var logRecords: LinkedList<String> = LinkedList<String>().apply {
            add("Log messages:\n")
        }

        @JvmStatic
        fun registerLoggerHandler() {
            if (logRecords.size <= 1) { // initialization log is size 1
                Logger.getLogger("").addHandler(object : Handler() {
                    override fun publish(logRecord: LogRecord) {
                        val loggerNameParts = logRecord.loggerName.split(".")
                        val loggerName = loggerNameParts.lastOrNull() ?: logRecord.loggerName
                        if (logRecords.size > 100) logRecords.removeFirst()
                        val entry = "[${loggerName}] ${logRecord.message}"
                        logRecords.add(entry)
                    }

                    override fun flush() {
                    }

                    override fun close() {
                    }
                })
            }
        }

        @JvmStatic
        fun getCurrLogs() : List<String> {
            return LinkedList(logRecords).toList()
        }
    }
}
