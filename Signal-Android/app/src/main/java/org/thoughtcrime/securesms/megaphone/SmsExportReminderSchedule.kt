package org.thoughtcrime.securesms.megaphone

import android.content.Context
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.SmsExportPhase
import org.thoughtcrime.securesms.util.Util
import kotlin.time.Duration.Companion.days

class SmsExportReminderSchedule(private val context: Context) : MegaphoneSchedule {

  companion object {
    @JvmStatic
    var showPhase3Megaphone = true
  }

  private val basicMegaphoneSchedule = RecurringSchedule(3.days.inWholeMilliseconds)
  private val fullScreenSchedule = RecurringSchedule(1.days.inWholeMilliseconds)

  @WorkerThread
  override fun shouldDisplay(seenCount: Int, lastSeen: Long, firstVisible: Long, currentTime: Long): Boolean {
    return if (Util.isDefaultSmsProvider(context)) {
      when (SignalStore.misc().smsExportPhase) {
        SmsExportPhase.PHASE_1 -> basicMegaphoneSchedule.shouldDisplay(seenCount, lastSeen, firstVisible, currentTime)
        SmsExportPhase.PHASE_2 -> fullScreenSchedule.shouldDisplay(seenCount, lastSeen, firstVisible, currentTime)
        SmsExportPhase.PHASE_3 -> showPhase3Megaphone
      }
    } else {
      false
    }
  }
}
