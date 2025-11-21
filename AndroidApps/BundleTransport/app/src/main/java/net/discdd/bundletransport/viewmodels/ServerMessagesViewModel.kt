package net.discdd.bundletransport.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundletransport.utils.ServerMessage
import net.discdd.bundletransport.utils.ServerMessageRepository

fun sampleMessages(): List<ServerMessage> {
    return listOf (
        ServerMessage().apply {
            messageId = 1
            date = "11/17/25"
            message = "test #1"
            read = false
        },
        ServerMessage().apply {
            messageId = 2
            date = "11/17/25"
            message = "test #2"
            read = false
        },
        ServerMessage().apply {
            messageId = 3
            date = "11/17/25"
            message =
                "hello this is a test notification for a really long notification hello hello testing one two three hello hi"
            read = false
        },
        ServerMessage().apply {
            messageId = 4
            date = "11/17/25"
            message = "لجمل الطويلة · جمل طويلة · عقوبات طويلة · أحكام سجن طويلة · عقوبات مطولة"
            read = false
        }
    )
}

//TODO:
//refresh
class ServerMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServerMessageRepository(app)
    val messages: LiveData<List<ServerMessage>> = repository.getAllServerMessages()

    init {
        //for sample messages!! do not push to main!!
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedSampleMessages(sampleMessages())
        }
    }

    fun markRead(messageId: Int) {
        repository.markRead(messageId)
    }

    fun deleteById(messageId: Int) {
        repository.markRead(messageId)
        repository.insertAll(sampleMessages())
    }

    /*
    //private val _notifs = MutableStateFlow(sampleNotifs())
    //val notifs = _notifs.asStateFlow()

    init {
        //insert messages into repo
    }

    fun markRead(id: String) {
        _notifs.update { currentList ->
            currentList.map { notif ->
                if (notif.id == id && !notif.read) notif.copy(read = true) else notif
            }
        }
    }
    fun refresh() {

    }
    fun deleteNotif(id: String) {
        _notifs.update { currentList ->
            currentList.filterNot{it.id == id}
        }
    } */
}