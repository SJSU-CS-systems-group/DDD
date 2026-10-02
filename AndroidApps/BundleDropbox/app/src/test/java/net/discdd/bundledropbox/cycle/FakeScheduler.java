package net.discdd.bundledropbox.cycle;

import java.util.PriorityQueue;

/**
 * Deterministic {@link TaskScheduler} + {@link Clock} for tests: virtual time that only moves when the
 * test calls {@link #advance(long)}; tasks run on the test thread in (due time, submission) order.
 */
final class FakeScheduler implements TaskScheduler, Clock {
    private static final class Task implements Comparable<Task>, Cancellable {
        final long due;
        final long seq;
        final Runnable r;
        boolean cancelled;

        Task(long due, long seq, Runnable r) {
            this.due = due;
            this.seq = seq;
            this.r = r;
        }

        @Override
        public int compareTo(Task o) {
            int c = Long.compare(due, o.due);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long now = 1_000; // non-zero origin to catch "deadline == 0" bugs
    private long seq;
    private boolean shutdown;

    @Override
    public long nowMillis() {
        return now;
    }

    @Override
    public void execute(Runnable task) {
        schedule(task, 0);
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs) {
        Task t = new Task(now + Math.max(0, delayMs), seq++, task);
        if (!shutdown) queue.add(t);
        return t;
    }

    @Override
    public void shutdown() {
        shutdown = true;
        queue.clear();
    }

    boolean isShutdown() {
        return shutdown;
    }

    /** Run every task due at the current time (including ones they enqueue for now). */
    void runReady() {
        Task t;
        while ((t = queue.peek()) != null && t.due <= now) {
            queue.poll();
            if (!t.cancelled) t.r.run();
        }
    }

    /** Move virtual time forward, running tasks at their due times. */
    void advance(long ms) {
        long target = now + ms;
        runReady();
        Task t;
        while ((t = queue.peek()) != null && t.due <= target) {
            now = Math.max(now, t.due);
            runReady();
        }
        now = target;
        runReady();
    }
}
