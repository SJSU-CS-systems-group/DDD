package net.discdd.bundletransport.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch
import net.discdd.bundletransport.BuildConfig
import net.discdd.bundletransport.utils.ServerMessage
import net.discdd.bundletransport.utils.ServerMessageRepository
import java.time.LocalDateTime

fun sampleMessages(): List<ServerMessage> {
    return listOf(
        ServerMessage().apply {
            messageId = 1
            date = LocalDateTime.of(2025, 11, 17, 10, 0)
            message = "test #1"
            isRead = false
        },
        ServerMessage().apply {
            messageId = 2
            date = LocalDateTime.of(2025, 11, 17, 10, 2)
            message = "test #2"
            isRead = false
        },
        ServerMessage().apply {
            messageId = 3
            date = LocalDateTime.of(2025, 11, 17, 10, 1)
            message =
                "hello this is a test notification for a really long notification hello hello testing one two three hello hi"
            isRead = false
        }
    )
}

//TODO: refresh
class ServerMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServerMessageRepository(app)
    val messages: LiveData<List<ServerMessage>> = repository.getAllServerMessages()

    init {
       if (BuildConfig.DEBUG) { //sample messages
            viewModelScope.launch(Dispatchers.IO) {
                repository.seedSampleMessages(sampleMessages())
            }
        }
    }

    fun markRead(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markRead(messageId)
        }
    }

    fun deleteById(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteById(messageId)
        }
    }
}