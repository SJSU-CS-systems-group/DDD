package net.discdd.bundledropbox.service;

import android.os.Handler;
import android.os.Looper;

import net.discdd.screens.LogFragment;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The one place the dropbox connects JUL to android-core's Logs tab. Both the activity and the service call
 * {@link #install()}; only the first call registers, and the handler android-core adds is wrapped in a
 * {@link SerializedLogHandler} confined to the main thread (see that class for why: audit M4).
 *
 * <p>Nothing else in the dropbox may call {@code LogFragment.registerLoggerHandler()} directly, or an
 * unwrapped, thread-unsafe handler would be installed again.
 */
public final class UiLogBridge {
    private static final AtomicBoolean installed = new AtomicBoolean();

    private UiLogBridge() {
    }

    /** Idempotent and thread-safe; call from any thread. */
    public static void install() {
        if (!installed.compareAndSet(false, true)) return;
        Handler main = new Handler(Looper.getMainLooper());
        SerializedLogHandler.wrapHandlersAddedBy(Logger.getLogger(""), LogFragment::registerLoggerHandler,
                                                 main::post);
    }
}
