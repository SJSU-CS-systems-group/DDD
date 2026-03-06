package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.discdd.bundletransport.utils.ServerMessage
import net.discdd.bundletransport.utils.ServerMessageRepository
import java.time.ZoneId

class ServerMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServerMessageRepository(app)
    val messages: LiveData<List<ServerMessage>> = repository.getAllServerMessages()

    private val _zoneId = MutableStateFlow(ZoneId.systemDefault())
    val zoneId: StateFlow<ZoneId> = _zoneId

    private val tzReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            _zoneId.value = ZoneId.systemDefault()
        }
    }

    init {
        app.registerReceiver(tzReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(tzReceiver)
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
