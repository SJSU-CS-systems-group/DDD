package net.discdd.bundledropbox.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link SerializedLogHandler}, the dropbox's mitigation of android-core's thread-unsafe
 * Logs-tab handler (audit M4).
 */
public class SerializedLogHandlerTest {
    /** Same shape as android-core's handler: an unsynchronized LinkedList capped at 101 entries. */
    private static final class UnsafeListHandler extends Handler {
        final LinkedList<String> records = new LinkedList<>();

        @Override
        public void publish(LogRecord r) {
            if (records.size() > 100) records.removeFirst();
            records.add(r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void concurrentPublishesAreConfinedToOneThread() throws Exception {
        UnsafeListHandler unsafe = new UnsafeListHandler();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        SerializedLogHandler safe = new SerializedLogHandler(unsafe, owner);
        int threads = 8;
        int perThread = 2_000;
        ExecutorService loggers = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            loggers.execute(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) safe.publish(new LogRecord(Level.INFO, "m" + i));
            });
        }
        go.countDown();
        loggers.shutdown();
        assertTrue(loggers.awaitTermination(30, TimeUnit.SECONDS));
        owner.shutdown();
        assertTrue(owner.awaitTermination(30, TimeUnit.SECONDS));
        // An unsynchronized list under 8 writers would lose its size invariant; confined, it stays exact.
        assertEquals(101, unsafe.records.size());
    }

    @Test
    public void aBrokenDelegateOrExecutorNeverThrowsIntoTheCaller() {
        Handler broken = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new java.util.NoSuchElementException("corrupted list");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        new SerializedLogHandler(broken, Runnable::run).publish(new LogRecord(Level.INFO, "x"));
        new SerializedLogHandler(new UnsafeListHandler(), r -> {
            throw new RejectedExecutionException("main looper gone");
        }).publish(new LogRecord(Level.INFO, "x"));
    }

    @Test
    public void wrapsOnlyTheHandlersTheRegistrationAdded() {
        Logger root = Logger.getAnonymousLogger();
        root.setUseParentHandlers(false);
        Handler existing = new UnsafeListHandler();
        root.addHandler(existing);
        UnsafeListHandler added = new UnsafeListHandler();
        List<Runnable> posted = new ArrayList<>();

        int n = SerializedLogHandler.wrapHandlersAddedBy(root, () -> root.addHandler(added), posted::add);

        assertEquals(1, n);
        Handler[] handlers = root.getHandlers();
        assertEquals(2, handlers.length);
        assertSame(existing, handlers[0]);
        assertSame(added, ((SerializedLogHandler) handlers[1]).delegate());
        root.info("hello");
        assertEquals("published only on the owner executor", 0, added.records.size());
        posted.forEach(Runnable::run);
        assertEquals(List.of("hello"), new ArrayList<>(added.records));
    }
}
