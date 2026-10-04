package net.discdd.bundledropbox.cycle;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link TaskScheduler}: a single-thread {@link ScheduledThreadPoolExecutor} named
 * {@code dropbox-cycle}. This is the "one single-thread executor owns all state" of 03 Architecture.
 *
 * <p>R5 §4: the stock {@code DDDFixedRateScheduler} is minutes-only with fixed delay, so the role cycle
 * uses its own second-level timers here.
 *
 * <p>Time base: a {@code ScheduledExecutorService} measures delays with {@code System.nanoTime()}
 * ({@code CLOCK_MONOTONIC}), which stops while the CPU is suspended. Being plugged in disables Doze but
 * does <em>not</em> stop the kernel from suspending with the screen off, so a delay here can stretch by
 * however long the device slept (Audit M1/M2). The controller therefore uses this class only to wake up:
 * it keeps every deadline on its {@link Clock} ({@code elapsedRealtime}, which counts through suspend),
 * hands over delays of at most {@code RoleCycleController.TIMER_SLICE_MS}, and re-checks its deadlines on
 * every wake-up and event. The service also holds a partial wake lock ({@code keep_awake}, on by default for
 * the stationary relay), so in practice the CPU does not suspend. AlarmManager is not used: exact alarms
 * every 30-60 s need the user-granted {@code SCHEDULE_EXACT_ALARM} permission on Android 12+, while-idle
 * alarms are rate-limited in Doze, and for a plugged-in relay the wake lock already keeps timers honest.
 */
public final class ExecutorTaskScheduler implements TaskScheduler {
    private final ScheduledThreadPoolExecutor executor;

    /** Create a scheduler backed by a new daemon thread called {@code dropbox-cycle}. */
    public ExecutorTaskScheduler() {
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "dropbox-cycle");
            t.setDaemon(true);
            return t;
        });
        // Cancelled dwell/drain timers are common (every early drain cancels the cap timer); drop them
        // from the queue right away instead of letting them sit there until their delay elapses.
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** @return the underlying executor (e.g. for awaitTermination in tests). */
    public ScheduledExecutorService executor() {
        return executor;
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs) {
        ScheduledFuture<?> f = executor.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return () -> f.cancel(false);
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}
