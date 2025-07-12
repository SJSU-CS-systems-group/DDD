package utils

import net.discdd.utils.UserLogRepository
import net.discdd.utils.UserLogRepository.UserLogComponent.WIFI
import org.junit.Assert
import org.junit.Test

class UserLogRepositoryTest {
    @Test
    fun logTest() {
        for (i in 0..40) {
            UserLogRepository.log(WIFI, "message $i")
        }
        val repo = UserLogRepository.getRepo(WIFI)
        Assert.assertEquals(20, repo.size)
        val now = System.currentTimeMillis()
        for (i in 21..40) {
            val logEntry = repo.get(i - 21)
            Assert.assertEquals(now.toDouble(), logEntry.time.toDouble(), 10.0)
            Assert.assertEquals("message $i", logEntry.message)
            Assert.assertEquals(UserLogRepository.UserLogLevel.INFO, logEntry.level)
            Assert.assertEquals(WIFI, logEntry.component)
        }
    }

}