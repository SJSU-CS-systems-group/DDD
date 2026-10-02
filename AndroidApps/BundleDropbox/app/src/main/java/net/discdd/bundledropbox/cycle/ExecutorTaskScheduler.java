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
 * uses its own second-level timers here. Note that a {@code ScheduledExecutorService} measures delays
 * with {@code System.nanoTime()}, which on Android stops in deep sleep; the dropbox is assumed plugged in
 * with a foreground service (02 Decisions), and the controller computes deadlines from the injected
 * {@link Clock} ({@code elapsedRealtime}), so a late wake-up only delays a switch and never extends a
 * deadline. FUTURE: use AlarmManager or a wake lock if unplugged operation is ever needed.
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
