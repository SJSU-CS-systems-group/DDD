package net.discdd.bundledropbox.cycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;

/**
 * Shared wiring for the fake-clock controller tests: a {@link FakeScheduler} that is both clock and owner
 * thread, a {@link FakeRadio} completed by hand, a real {@link ActivityGate}, and a listener that records
 * modes, errors and pause reports.
 */
final class CycleHarness {
    static final long CLIENT = CycleConfig.DEFAULT_CLIENT_PERIOD_MS;
    static final long TRANSPORT = CycleConfig.DEFAULT_TRANSPORT_PERIOD_MS;
    static final long CAP = CycleConfig.DEFAULT_DRAIN_CAP_MS;

    final FakeScheduler s = new FakeScheduler();
    final FakeRadio radio = new FakeRadio();
    final AtomicBoolean blob = new AtomicBoolean(true);
    final ActivityGate gate = new ActivityGate();
    final List<CycleMode> modes = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<Boolean> pauseReports = new ArrayList<>();
    final RoleCycleController c;

    CycleHarness() {
        this(CycleConfig.DEFAULTS);
    }

    CycleHarness(CycleConfig config) {
        c = new RoleCycleController(radio, s, s, gate, blob::get, config);
        c.addListener(new CycleListener() {
            @Override
            public void onModeChanged(CycleMode previous, CycleMode current, long startedAt, long deadlineAt) {
                modes.add(current);
            }

            @Override
            public void onCycleError(CycleMode mode, String reason) {
                errors.add(mode + ":" + reason);
            }

            @Override
            public void onPausedChanged(boolean paused) {
                pauseReports.add(paused);
            }
        });
    }

    /** Complete the most recent radio call successfully and let the controller react. */
    void ready() {
        radio.last().ready();
        s.runReady();
    }

    /** Fail the most recent radio call and let the controller react. */
    void fail(String why) {
        radio.last().fail(why);
        s.runReady();
    }

    void startToHosting() {
        c.start();
        s.runReady();
        assertEquals("startHosting", radio.last().op);
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
    }

    /** Fail the pending op and each of its backoff retries, ending in fallback or UNAVAILABLE. */
    void failAllAttempts() {
        for (int i = 0; i < RoleCycleController.MAX_ATTEMPTS; i++) {
            fail("busy");
            if (i < RoleCycleController.MAX_ATTEMPTS - 1) s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS << i);
        }
    }
}
