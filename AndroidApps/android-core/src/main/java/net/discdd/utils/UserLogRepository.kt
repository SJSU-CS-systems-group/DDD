package net.discdd.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.logging.Level

/**
 * this class collects logs to be displayed to a user. it is mainly
 * used by a service but may also be used by an activity.
 */
object UserLogRepository {
    const val MAX_LOG_ENTRIES = 20
    enum class UserLogType{ WIFI, EXCHANGE }
    data class UserLogEntry(val type: UserLogType, val time: Long, val message:String, val level: Level = Level.INFO) {}
    private val repositories = mutableMapOf<UserLogType, MutableList<UserLogEntry>>()
    private val _event = MutableSharedFlow<Unit>(5)
    val event = _event.asSharedFlow()

    @Synchronized
    fun getRepo(component: UserLogType): List<UserLogEntry> {
        return repositories[component]!!.toList()
    }

    @Synchronized
    fun log(entry: UserLogEntry) {
        val repo = repositories.getOrPut(entry.type) {mutableListOf()}// we have a default, this should never be null
        if (repo.size >= MAX_LOG_ENTRIES) {
            repo.removeAt(0)
        }
        repo.add(entry)
        _event.tryEmit(Unit)
    }
    fun log(userLogType: UserLogType, message: String, time: Long = System.currentTimeMillis(), level: Level = Level.INFO) {
        log(UserLogEntry(userLogType, time, message, level))
    }
}