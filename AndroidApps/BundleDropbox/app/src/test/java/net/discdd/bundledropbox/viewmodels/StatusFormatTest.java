package net.discdd.bundledropbox.viewmodels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.discdd.bundledropbox.cycle.CycleListener;
import net.discdd.bundledropbox.cycle.CycleMode;
import net.discdd.bundledropbox.status.DropboxStatus;

import org.junit.Test;

/** JVM tests for the UI's pure presentation helpers ({@link StatusFormat}, {@link SettingsValidation}). */
public class StatusFormatTest {
    private static DropboxStatus status(CycleMode mode, long blobTs, int sessions) {
        return DropboxStatus.builder().mode(mode).recencyBlobTimestamp(blobTs).activeSessions(sessions).build();
    }

    @Test
    public void operatorStateMapsEveryModeWithBlob() {
        long blob = 1_700_000_000_000L;
        assertEquals(OperatorState.NOT_RUNNING, StatusFormat.operatorState(status(CycleMode.STOPPED, blob, 0), false));
        assertEquals(OperatorState.PAUSED, StatusFormat.operatorState(status(CycleMode.STOPPED, blob, 0), true));
        assertEquals(OperatorState.RESTORING_CLIENT_SERVICE,
                     StatusFormat.operatorState(status(CycleMode.HOST_STARTING, blob, 0), false));
        assertEquals(OperatorState.ACCEPTING_CLIENTS, StatusFormat.operatorState(status(CycleMode.HOSTING, blob, 2), false));
        assertEquals(OperatorState.FINISHING_CLIENT_TRANSFERS,
                     StatusFormat.operatorState(status(CycleMode.HOST_DRAINING, blob, 1), false));
        assertEquals(OperatorState.SWITCHING_TO_TRANSPORT,
                     StatusFormat.operatorState(status(CycleMode.HOST_STOPPING, blob, 0), false));
        assertEquals(OperatorState.SEARCHING_FOR_TRANSPORT,
                     StatusFormat.operatorState(status(CycleMode.VISIT_STARTING, blob, 0), false));
        assertEquals(OperatorState.SEARCHING_FOR_TRANSPORT,
                     StatusFormat.operatorState(status(CycleMode.VISITING, blob, 0), false));
        assertEquals(OperatorState.EXCHANGING_WITH_TRANSPORT,
                     StatusFormat.operatorState(status(CycleMode.VISITING, blob, 1), false));
        assertEquals(OperatorState.FINISHING_TRANSPORT_TRANSFER,
                     StatusFormat.operatorState(status(CycleMode.VISIT_DRAINING, blob, 1), false));
        assertEquals(OperatorState.RESTORING_CLIENT_SERVICE,
                     StatusFormat.operatorState(status(CycleMode.VISIT_STOPPING, blob, 0), false));
        assertEquals(OperatorState.NEEDS_ATTENTION, StatusFormat.operatorState(status(CycleMode.UNAVAILABLE, blob, 0), false));
    }

    @Test
    public void operatorStopWinsOverTheLastSnapshot() {
        // A stopped service publishes nothing more, so the persisted flag decides (audit m15).
        assertEquals(OperatorState.STOPPED_BY_OPERATOR,
                     StatusFormat.operatorState(status(CycleMode.HOSTING, 1L, 0), false, true));
        assertEquals(OperatorState.STOPPED_BY_OPERATOR,
                     StatusFormat.operatorState(status(CycleMode.STOPPED, 1L, 0), true, true));
        assertEquals(OperatorState.ACCEPTING_CLIENTS,
                     StatusFormat.operatorState(status(CycleMode.HOSTING, 1L, 0), false, false));
    }

    @Test
    public void bootstrapWithoutBlobShowsWaitingButStillShowsExchange() {
        assertEquals(OperatorState.WAITING_FOR_FIRST_VISIT,
                     StatusFormat.operatorState(status(CycleMode.VISIT_STARTING, 0, 0), false));
        assertEquals(OperatorState.WAITING_FOR_FIRST_VISIT, StatusFormat.operatorState(status(CycleMode.VISITING, 0, 0), false));
        assertEquals(OperatorState.WAITING_FOR_FIRST_VISIT,
                     StatusFormat.operatorState(status(CycleMode.VISIT_STOPPING, 0, 0), false));
        assertEquals(OperatorState.EXCHANGING_WITH_TRANSPORT,
                     StatusFormat.operatorState(status(CycleMode.VISITING, 0, 1), false));
    }

    @Test
    public void countdownAndProgressUseMonotonicDeadline() {
        DropboxStatus s = DropboxStatus.builder().cycle(CycleMode.HOSTING, 10_000, 70_000).build();
        assertEquals(Long.valueOf(30_000), StatusFormat.remainingMillis(s, 40_000));
        assertEquals(Long.valueOf(0), StatusFormat.remainingMillis(s, 90_000));
        assertEquals(0.5f, StatusFormat.elapsedFraction(s, 40_000), 1e-6);
        assertEquals(1f, StatusFormat.elapsedFraction(s, 90_000), 1e-6);
        assertEquals(0f, StatusFormat.elapsedFraction(s, 5_000), 1e-6);

        DropboxStatus none = DropboxStatus.builder().cycle(CycleMode.HOST_STARTING, 10_000, CycleListener.NO_DEADLINE).build();
        assertNull(StatusFormat.remainingMillis(none, 40_000));
        assertNull(StatusFormat.elapsedFraction(none, 40_000));
    }

    @Test
    public void formatting() {
        assertEquals("0:00", StatusFormat.formatCountdown(-5));
        assertEquals("0:01", StatusFormat.formatCountdown(1)); // rounds up: never 0:00 while time remains
        assertEquals("1:00", StatusFormat.formatCountdown(60_000));
        assertEquals("1:01:05", StatusFormat.formatCountdown(3_665_000));
        assertEquals("0 s", StatusFormat.formatAge(-10_000));
        assertEquals("59 s", StatusFormat.formatAge(59_999));
        assertEquals("2 min", StatusFormat.formatAge(120_000));
        assertEquals("3 h", StatusFormat.formatAge(3 * 3_600_000L));
        assertEquals("2 d", StatusFormat.formatAge(2 * 86_400_000L));
        assertEquals("512 B", StatusFormat.formatBytes(512));
        assertEquals("1.5 KB", StatusFormat.formatBytes(1536));
        assertEquals("1.0 GB", StatusFormat.formatBytes(1L << 30));
    }

    @Test
    public void settingsValidation() {
        assertEquals(Integer.valueOf(60), SettingsValidation.parseInRange(" 60 ", 1, 100));
        assertNull(SettingsValidation.parseInRange("0", 1, 100));
        assertNull(SettingsValidation.parseInRange("", 1, 100));
        assertNull(SettingsValidation.parseInRange("abc", 1, 100));
        assertEquals(Integer.valueOf(0), SettingsValidation.parseInRange("0", 0, 100));
    }
}
