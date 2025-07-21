package net.discdd.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    public static final String SVC_POWER_LOCK = "net.discdd.scheduler::SvcPowerLock";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private void doWakeLock(boolean running) {
        if (running) {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } else {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        }
    }


    /**
     * the callable will be run periodically. the same callable will be used repeatedly.
     * @param callable the callable to run periodically. it should not throw any exceptions.
     */
    public DDDFixedRateScheduler(Context context, Callable<T> callable) {
        this.callable = callable;
        var pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SVC_POWER_LOCK);
        var wm = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, SVC_POWER_LOCK);
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
            doWakeLock(false);
        }
        if (periodInMinutes <= 0) {
            return;
        }
        doWakeLock(true);
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
        }, periodInMinutes, periodInMinutes, TimeUnit.MINUTES);
    }

    /**
     * does one time invocation of the callable. it will not affect the periodically scheduled callables.
     * it also will not affect lastResult and lastCallable
     *
     * @return the future representing pending callable.
     */
    public Future<T> callItNow() {
        return scheduler.submit(callable);
    }

    int getPeriodInMinutes() {return periodInMinutes;}

    public Future<T> callItNow(Callable<T> oneTimeCallable) {
        return scheduler.submit(oneTimeCallable);
    }
}
