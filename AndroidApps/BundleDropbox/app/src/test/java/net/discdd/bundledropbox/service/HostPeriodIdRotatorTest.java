package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.cycle.CycleConfig;
import net.discdd.bundledropbox.cycle.CycleMode;
import net.discdd.bundledropbox.cycle.RadioCallback;
import net.discdd.bundledropbox.cycle.RadioDriver;
import net.discdd.bundledropbox.cycle.RoleCycleController;
import net.discdd.bundledropbox.cycle.TaskScheduler;
import net.discdd.bundledropbox.cycle.VisitCallback;
import net.discdd.bundledropbox.store.DropboxPaths;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link HostPeriodIdRotator} (audit M8): a new advertised id for every hosting period, visible
 * to the radio when it builds the TXT record, and persisted.
 */
public class HostPeriodIdRotatorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxIdentity identity(DropboxPaths p, boolean rotate) throws Exception {
        return DropboxIdentity.load(p, DropboxIdentityTest.ANDROID_DEFAULT_BASE64, rotate);
    }

    @Test
    public void bumpsOnEveryEntryIntoHostStartingOnly() throws Exception {
        DropboxPaths p = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
        DropboxIdentity id = identity(p, true);
        List<String> logged = new ArrayList<>();
        HostPeriodIdRotator rotator = new HostPeriodIdRotator(id, logged::add);

        rotator.onModeChanged(CycleMode.STOPPED, CycleMode.HOST_STARTING, 0, -1);
        assertEquals(id.baseId() + "-1", id.advertisedId());
        rotator.onModeChanged(CycleMode.HOST_STARTING, CycleMode.HOST_STARTING, 0, -1); // backoff retry
        rotator.onModeChanged(CycleMode.HOST_STARTING, CycleMode.HOSTING, 0, -1);
        rotator.onModeChanged(CycleMode.HOSTING, CycleMode.HOST_DRAINING, 0, -1);
        rotator.onModeChanged(CycleMode.VISIT_STOPPING, CycleMode.VISIT_STARTING, 0, -1);
        assertEquals(1, id.generation());
        // A VISIT period that found no carrier still gives the next HOST period a new id (the old rule
        // rotated only after a visit that fetched a blob).
        rotator.onModeChanged(CycleMode.VISIT_STOPPING, CycleMode.HOST_STARTING, 0, -1);
        rotator.onModeChanged(CycleMode.UNAVAILABLE, CycleMode.HOST_STARTING, 0, -1);
        assertEquals(3, id.generation());
        assertEquals(List.of(id.baseId() + "-1", id.baseId() + "-2", id.baseId() + "-3"), logged);

        assertEquals("persisted across restarts", 3, identity(p, true).generation());
    }

    @Test
    public void rotationOffKeepsThePlainBaseId() throws Exception {
        DropboxPaths p = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
        DropboxIdentity id = identity(p, false);
        new HostPeriodIdRotator(id, s -> { }).onModeChanged(CycleMode.VISIT_STOPPING, CycleMode.HOST_STARTING, 0, -1);
        assertEquals(0, id.generation());
        assertEquals(id.baseId(), id.advertisedId());
    }

    /**
     * Against the real controller: the listener runs before {@code startHosting}, so every hosting period
     * advertises a fresh id, including after a VISIT period that found no carrier.
     */
    @Test
    public void everyHostingPeriodAdvertisesAFreshId() throws Exception {
        DropboxPaths p = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
        DropboxIdentity id = identity(p, true);
        VirtualScheduler sched = new VirtualScheduler();
        List<String> advertised = new ArrayList<>();
        RadioDriver radio = new RadioDriver() {
            @Override
            public void startHosting(long gen, RadioCallback cb) {
                advertised.add(id.advertisedId()); // what the Wi-Fi host puts in the TXT record
                cb.ready(gen);
            }

            @Override
            public void stopHosting(long gen, RadioCallback cb) {
                cb.ready(gen);
            }

            @Override
            public void withdrawHostAdvertisement() {
            }

            @Override
            public void startVisit(long gen, VisitCallback cb) {
                cb.ready(gen); // no carrier ever found
            }

            @Override
            public void stopVisit(long gen, RadioCallback cb) {
                cb.ready(gen);
            }
        };
        RoleCycleController cycle = new RoleCycleController(radio, sched, sched, new ActivityGate(), () -> true,
                                                            new CycleConfig(60_000, 30_000, 15_000, true));
        cycle.addListener(new HostPeriodIdRotator(id, s -> { }));
        cycle.start();
        sched.advance(5 * 90_000); // about five client+transport rounds

        assertTrue("several hosting periods", advertised.size() >= 4);
        assertEquals("a new id each period", advertised.size(), new HashSet<>(advertised).size());
        assertEquals(id.baseId() + "-1", advertised.get(0));
    }

    /** Minimal deterministic scheduler and clock: virtual time that moves only in {@link #advance}. */
    private static final class VirtualScheduler implements TaskScheduler, net.discdd.bundledropbox.cycle.Clock {
        private static final class Task {
            final long due;
            final long seq;
            final Runnable r;
            boolean cancelled;

            Task(long due, long seq, Runnable r) {
                this.due = due;
                this.seq = seq;
                this.r = r;
            }
        }

        private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> a.due != b.due ?
                Long.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
        private long now = 1_000;
        private long seq;

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
            queue.add(t);
            return () -> t.cancelled = true;
        }

        @Override
        public void shutdown() {
            queue.clear();
        }

        void advance(long ms) {
            long end = now + ms;
            Task t;
            while ((t = queue.peek()) != null && t.due <= end) {
                queue.poll();
                now = Math.max(now, t.due);
                if (!t.cancelled) t.r.run();
            }
            now = end;
        }
    }
}
