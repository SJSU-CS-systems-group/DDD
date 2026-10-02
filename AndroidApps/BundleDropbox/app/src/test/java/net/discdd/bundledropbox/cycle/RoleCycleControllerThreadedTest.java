package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Smoke test with the real {@link ExecutorTaskScheduler} and a radio that completes every op on a
 * different thread (like Wi-Fi P2P callbacks on the main looper): the cycle must oscillate and shut down.
 */
public class RoleCycleControllerThreadedTest {
    /** Radio that reports success from a fresh thread after a short delay. */
    private static final class AsyncRadio implements RadioDriver {
        private void later(Runnable r) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                }
                r.run();
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void startHosting(long gen, RadioCallback cb) {
            later(() -> cb.ready(gen));
        }

        @Override
        public void stopHosting(long gen, RadioCallback cb) {
            later(() -> cb.ready(gen));
        }

        @Override
        public void withdrawHostAdvertisement() {
        }

        @Override
        public void startVisit(long gen, VisitCallback cb) {
            later(() -> cb.ready(gen));
        }

        @Override
        public void stopVisit(long gen, RadioCallback cb) {
            later(() -> cb.ready(gen));
        }
    }

    @Test(timeout = 10_000)
    public void oscillatesOnRealThreadsAndShutsDown() throws Exception {
        ExecutorTaskScheduler sched = new ExecutorTaskScheduler();
        Clock clock = () -> System.nanoTime() / 1_000_000;
        RoleCycleController c = new RoleCycleController(new AsyncRadio(), clock, sched, new ActivityGate(),
                                                        () -> true, new CycleConfig(40, 40, 20, true));
        List<CycleMode> dwells = new CopyOnWriteArrayList<>();
        CountDownLatch fourDwells = new CountDownLatch(4);
        CountDownLatch stopped = new CountDownLatch(1);
        c.addListener((prev, cur, at, deadline) -> {
            if (cur.isDwelling()) {
                dwells.add(cur);
                fourDwells.countDown();
            }
            if (cur == CycleMode.STOPPED) stopped.countDown();
        });
        c.start();
        assertTrue(fourDwells.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(CycleMode.HOSTING, CycleMode.VISITING, CycleMode.HOSTING, CycleMode.VISITING),
                     dwells.subList(0, 4));
        c.shutdown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertTrue(sched.executor().awaitTermination(5, TimeUnit.SECONDS));
    }
}
