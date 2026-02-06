package net.discdd.bundleclient.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData

import net.discdd.bundleclient.BuildConfig
import net.discdd.bundleclient.utils.ServerMessage
import net.discdd.bundleclient.utils.ServerMessageRepository
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
            repository.seedSampleMessages(sampleMessages())
        }
    }

    fun markRead(messageId: Long) {
        repository.markRead(messageId)
    }

    fun deleteById(messageId: Long) {
        repository.deleteById(messageId)
    }
}
