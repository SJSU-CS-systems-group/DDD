package org.thoughtcrime.securesms.jobs

import org.thoughtcrime.securesms.jobmanager.Data
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.transport.RetryLaterException
import java.lang.Exception
import kotlin.time.Duration.Companion.minutes

/**
 * Optimizes the message search index incrementally.
 */
class OptimizeMessageSearchIndexJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    const val KEY = "OptimizeMessageSearchIndexJob"

    @JvmStatic
    fun enqueue() {
      // TODO [greyson] Temporarily disabled until we can figure out what to do.
//      ApplicationDependencies.getJobManager().add(OptimizeMessageSearchIndexJob())
    }
  }

  constructor() : this(
    Parameters.Builder()
      .setQueue("OptimizeMessageSearchIndexJob")
      .setMaxAttempts(5)
      .setMaxInstancesForQueue(2)
      .build()
  )

  override fun serialize(): Data = Data.EMPTY
  override fun getFactoryKey() = KEY
  override fun onFailure() = Unit
  override fun onShouldRetry(e: Exception) = e is RetryLaterException
  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long = 1.minutes.inWholeMilliseconds

  override fun onRun() {
    // TODO [greyson] Temporarily disabled until we can figure out what to do.
//    val success = SignalDatabase.messageSearch.optimizeIndex(10.seconds.inWholeMilliseconds)
//
//    if (!success) {
//      throw RetryLaterException()
//    }
  }

  class Factory : Job.Factory<OptimizeMessageSearchIndexJob> {
    override fun create(parameters: Parameters, data: Data) = OptimizeMessageSearchIndexJob(parameters)
  }
}
