package net.discdd.bundleclient.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import net.discdd.bundleclient.utils.ServerMessage
import net.discdd.bundleclient.utils.ServerMessageRepository

class ServerMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServerMessageRepository(app)
    val messages: LiveData<List<ServerMessage>> = repository.getAllServerMessages()

    fun markRead(messageId: Long) {
        repository.markRead(messageId)
    }

    fun deleteById(messageId: Long) {
        repository.deleteById(messageId)
    }
}
