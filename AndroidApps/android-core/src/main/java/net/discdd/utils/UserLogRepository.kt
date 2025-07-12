package net.discdd.utils

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * this class collects logs to be displayed to a user. it is mainly
 * used by a service but may also be used by an activity.
 */
object UserLogRepository {
    const val MAX_LOG_ENTRIES = 20
    enum class UserLogComponent{ WIFI, EXCHANGE }
    enum class UserLogLevel{ ERROR, WARN, INFO }
    data class UserLogEntry(val component: UserLogComponent, val time: Long, val message:String, val level: UserLogLevel = UserLogLevel.INFO) {}
    private val repositories = mutableMapOf<UserLogComponent, MutableList<UserLogEntry>>()
    private val _event = MutableSharedFlow<Void>(0)
    private val event = _event.to

    fun getRepo(component: UserLogComponent): List<UserLogEntry> {
        return repositories[component]!!
    }
    fun log(entry: UserLogEntry) {
        val repo = repositories.getOrPut(entry.component) {mutableListOf()}// we have a default, this should never be null
        if (repo.size >= MAX_LOG_ENTRIES) {
            repo.removeAt(0)
        }
        repo.add(entry)
    }
    fun log(userLogComponent: UserLogComponent, message: String, time: Long = System.currentTimeMillis(), level: UserLogLevel = UserLogLevel.INFO) {
        log(UserLogEntry(userLogComponent, time, message, level))
    }
}