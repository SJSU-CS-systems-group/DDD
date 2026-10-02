package net.discdd.bundledropbox.cycle;

/**
 * The single thread that owns all {@link RoleCycleController} state, plus delayed tasks on that thread.
 *
 * <p>Abstracted (instead of using a {@code ScheduledExecutorService} directly) so unit tests can drive
 * the role cycle with a deterministic fake that runs tasks in virtual time together with a fake
 * {@link Clock}. Production code uses {@link ExecutorTaskScheduler}.
 *
 * <p>Contract: every task submitted through {@link #execute} or {@link #schedule} runs on the same
 * thread, one at a time, in submission order for equal due times. Both methods may be called from any
 * thread.
 */
public interface TaskScheduler {
    /** Handle to a delayed task. */
    interface Cancellable {
        /** Prevent the task from running if it has not started. Idempotent. */
        void cancel();
    }

    /**
     * Run {@code task} on the owner thread as soon as possible.
     *
     * @throws java.util.concurrent.RejectedExecutionException if the scheduler has been shut down
     */
    void execute(Runnable task);

    /**
     * Run {@code task} on the owner thread after {@code delayMs} milliseconds (0 or negative = asap).
     *
     * @return a handle that cancels the task
     * @throws java.util.concurrent.RejectedExecutionException if the scheduler has been shut down
     */
    Cancellable schedule(Runnable task, long delayMs);

    /** Stop accepting tasks and drop pending ones. Idempotent. */
    void shutdown();
}
