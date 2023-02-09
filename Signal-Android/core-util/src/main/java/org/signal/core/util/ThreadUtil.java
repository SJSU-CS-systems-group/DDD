package org.signal.core.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.signal.core.util.concurrent.TracingExecutor;
import org.signal.core.util.concurrent.TracingExecutorService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Thread related utility functions.
 */
public final class ThreadUtil {

  private static volatile Handler handler;

  @VisibleForTesting
  public static volatile  boolean enforceAssertions = true;

  private ThreadUtil() {}

  private static Handler getHandler() {
    if (handler == null) {
      synchronized (ThreadUtil.class) {
        if (handler == null) {
          handler = new Handler(Looper.getMainLooper());
        }
      }
    }
    return handler;
  }

  public static boolean isMainThread() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  public static void assertMainThread() {
    if (!isMainThread() && enforceAssertions) {
      throw new AssertionError("Must run on main thread.");
    }
  }

  public static void assertNotMainThread() {
    if (isMainThread() && enforceAssertions) {
      throw new AssertionError("Cannot run on main thread.");
    }
  }

  public static void postToMain(final @NonNull Runnable runnable) {
    getHandler().post(runnable);
  }

  public static void runOnMain(final @NonNull Runnable runnable) {
    if (isMainThread()) runnable.run();
    else                getHandler().post(runnable);
  }

  public static void runOnMainDelayed(final @NonNull Runnable runnable, long delayMillis) {
    getHandler().postDelayed(runnable, delayMillis);
  }

  public static void cancelRunnableOnMain(@NonNull Runnable runnable) {
    getHandler().removeCallbacks(runnable);
  }

  public static void runOnMainSync(final @NonNull Runnable runnable) {
    if (isMainThread()) {
      runnable.run();
    } else {
      final CountDownLatch sync = new CountDownLatch(1);
      runOnMain(() -> {
        try {
          runnable.run();
        } finally {
          sync.countDown();
        }
      });
      try {
        sync.await();
      } catch (InterruptedException ie) {
        throw new AssertionError(ie);
      }
    }
  }

  public static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public static void interruptableSleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) { }
  }

  public static Executor trace(Executor executor) {
    return new TracingExecutor(executor);
  }

  public static ExecutorService trace(ExecutorService executor) {
    return new TracingExecutorService(executor);
  }
}
