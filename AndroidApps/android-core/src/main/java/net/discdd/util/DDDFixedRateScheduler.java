package net.discdd.util;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * This is a single threaded scheduler that periodically runs the given Callable.
 * The period is expressed in terms of minutes. If minutes are <= 0, nothing will
 * be scheduled. The initial default is 0.
 *
 * @param <T>
 */
public class DDDFixedRateScheduler<T> {
    private final Callable<T> callable;
    private int periodInMinutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public T lastResult;
    public Exception lastException;
    public long lastExecutionStart;
    public long lastExecutionFinish;
    private ScheduledFuture<?> scheduleFuture;

    public DDDFixedRateScheduler(Callable<T> callable) {
        this.callable = callable;
    }

    /**
     * sets a new period for scheduling. any outstanding future invocations will be canceled, before
     * new invocations scheduled using the given period.
     *
     * @param periodInMinutes the period between runs of the callable. if <= 0, periodic scheduling will
     *                        be canceled.
     */
    synchronized public void setPeriodInMinutes(int periodInMinutes) {
        this.periodInMinutes = periodInMinutes;
        if (this.scheduleFuture != null) {
            this.scheduleFuture.cancel(false);
        }
        if (periodInMinutes <= 0) {
            return;
        }
        this.scheduleFuture = scheduler.scheduleWithFixedDelay(() -> {
            // in theory, we don't need to worry about races (and thus no synchronized) here
            // since we are running in a single threaded scheduler
            try {
                lastExecutionStart = System.currentTimeMillis();
                lastExecutionFinish = 0;
                lastResult = callable.call();
                lastException = null;
            } catch (Exception e) {
                lastResult = null;
                lastException = e;
            } finally {
                lastExecutionFinish = System.currentTimeMillis();
            }
        }, 0, periodInMinutes, TimeUnit.MINUTES);
    }

    /**
     * does one time invocation of the callable. it will not affect the periodically scheduled callables.
     * it also will not affect lastResult and lastCallable
     * @return the future representing pending callable.
     */
    public Future<T> callItNow() {
        return scheduler.submit(callable);
    }

    int getPeriodInMinutes() { return periodInMinutes; }
}
