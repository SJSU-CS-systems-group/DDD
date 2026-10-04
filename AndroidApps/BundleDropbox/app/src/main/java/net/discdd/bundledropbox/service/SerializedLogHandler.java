package net.discdd.bundledropbox.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Wraps a JUL {@link Handler} that is not thread-safe so it can neither corrupt its own state nor throw into
 * the thread that logged.
 *
 * <p><b>Why (audit M4, {@code log-handler-race-wedges-cycle}).</b> android-core's Logs-tab handler
 * ({@code LogViewModel.registerLoggerHandler}) appends to a static, unsynchronized {@code LinkedList} from
 * whichever thread logs. The dropbox logs from at least six threads, so concurrent publishes can corrupt the
 * list until every publish throws. JUL does not catch handler exceptions, so the exception escaped
 * {@code Logger.log} on the role-cycle thread and could leave the cycle in a {@code *_STARTING} mode with no
 * timer. android-core must not be modified (it is shared with BundleClient/BundleTransport), so the dropbox
 * mitigates it here:
 * <ul>
 *   <li>Every record is handed to one {@code confinedTo} executor (the main thread in production). The UI's
 *       copy of that list ({@code LogViewModel.refreshLogMsgs}) also runs on the main thread, so all access to
 *       the list becomes single-threaded and the race disappears, not just its symptom.</li>
 *   <li>{@link #publish} never throws: a rejected post or a failing delegate is swallowed (and reported once
 *       on {@code System.err}, never through JUL, which would recurse).</li>
 * </ul>
 * Cost: one main-thread post per published record (INFO and above). Order is preserved because the main
 * looper is FIFO. Residual: android-core's {@code getCurrLogs()} for bug reports copies the list from its own
 * dispatcher and can still race; that affects only the bug report, never the cycle threads.
 */
public final class SerializedLogHandler extends Handler {
    private final Handler delegate;
    private final Executor confinedTo;
    private final AtomicBoolean reported = new AtomicBoolean();

    /**
     * @param delegate   the unsafe handler; only ever called on {@code confinedTo}
     * @param confinedTo single-threaded executor that owns the delegate (main thread in production)
     */
    public SerializedLogHandler(Handler delegate, Executor confinedTo) {
        if (delegate == null || confinedTo == null) throw new NullPointerException("delegate and executor required");
        this.delegate = delegate;
        this.confinedTo = confinedTo;
    }

    /** @return the wrapped handler (tests). */
    Handler delegate() {
        return delegate;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        try {
            confinedTo.execute(() -> {
                try {
                    delegate.publish(record);
                } catch (Throwable t) {
                    reportOnce(t);
                }
            });
        } catch (Throwable t) { // e.g. RejectedExecutionException; logging must never fail the caller
            reportOnce(t);
        }
    }

    @Override
    public void flush() {
        // The delegate keeps nothing to flush (in-memory buffer); calling it off its thread would race.
    }

    @Override
    public void close() {
        // Not closed by us: the delegate belongs to android-core and lives as long as the process.
    }

    private void reportOnce(Throwable t) {
        if (reported.compareAndSet(false, true)) {
            System.err.println("DDD Dropbox: UI log handler failed; further failures are ignored: " + t);
        }
    }

    /**
     * Runs {@code register} (which adds handlers to the root logger) and replaces every handler it added with
     * a {@link SerializedLogHandler} confined to {@code confinedTo}. Handlers that were already installed are
     * left alone.
     *
     * @param root       the logger {@code register} adds to (the root logger in production)
     * @param register   the unsafe registration (android-core's {@code LogFragment.registerLoggerHandler})
     * @param confinedTo the executor every wrapped handler runs on
     * @return the number of handlers wrapped
     */
    public static int wrapHandlersAddedBy(Logger root, Runnable register, Executor confinedTo) {
        Set<Handler> before = new HashSet<>(Arrays.asList(root.getHandlers()));
        register.run();
        int wrapped = 0;
        for (Handler h : root.getHandlers()) {
            if (before.contains(h) || h instanceof SerializedLogHandler) continue;
            root.removeHandler(h);
            root.addHandler(new SerializedLogHandler(h, confinedTo));
            wrapped++;
        }
        return wrapped;
    }
}
