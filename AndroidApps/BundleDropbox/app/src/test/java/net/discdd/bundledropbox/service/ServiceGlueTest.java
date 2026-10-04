package net.discdd.bundledropbox.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM tests for the small service rules: {@link CycleEnvironmentBridge} (Review #10), {@link StatusErrors}
 * (audit m12) and {@link RelayPolicy} (audit m14, M1-M3, m15).
 */
public class ServiceGlueTest {
    @Test
    public void environmentAvailabilityNudgesTheCycle() {
        List<String> calls = new ArrayList<>();
        CycleEnvironmentBridge bridge = new CycleEnvironmentBridge(() -> calls.add("retryNow"),
                                                                   r -> calls.add("roleLost " + r),
                                                                   (a, r) -> calls.add("log " + a));
        bridge.onRadioAvailabilityChanged(true, "Wi-Fi Direct enabled");
        bridge.onRadioAvailabilityChanged(false, "Wi-Fi Direct disabled");
        bridge.onRoleLost("hosted group lost");
        assertEquals(List.of("log true", "retryNow",
                             "log false", "roleLost Wi-Fi Direct disabled",
                             "log false", "roleLost hosted group lost"), calls);
    }

    @Test
    public void aFailingLogLineDoesNotSwallowTheNudge() {
        List<String> calls = new ArrayList<>();
        CycleEnvironmentBridge bridge = new CycleEnvironmentBridge(() -> calls.add("retryNow"), r -> { },
                                                                   (a, r) -> {
                                                                       throw new IllegalStateException("log");
                                                                   });
        try {
            bridge.onRadioAvailabilityChanged(true, "enabled");
            fail("the log failure still propagates");
        } catch (IllegalStateException expected) {
            // the driver's main-thread caller catches it
        }
        assertEquals(List.of("retryNow"), calls);
    }

    @Test
    public void lastErrorClearsOnlyWhenItsSourceRecovers() {
        StatusErrors errors = new StatusErrors();
        errors.report(StatusErrors.Source.STARTUP, "Dropbox failed to start: x");
        assertFalse("a working role does not clear a startup error", errors.clear(StatusErrors.Source.RADIO));
        assertTrue(errors.clear(StatusErrors.Source.STARTUP));
        assertNull(errors.current());

        errors.report(StatusErrors.Source.VISIT, "Visit: upload failed");
        assertFalse("hosting again does not hide a failed visit", errors.clear(StatusErrors.Source.RADIO));
        assertEquals("Visit: upload failed", errors.current());
        errors.report(StatusErrors.Source.RADIO, "createGroup failed");
        assertFalse("a newer error replaced the visit error", errors.clear(StatusErrors.Source.VISIT));
        assertTrue(errors.clear(StatusErrors.Source.RADIO));
        assertNull(errors.current());
    }

    @Test
    public void autoStartOnBootAndUpdateUnlessDisabledOrStopped() {
        assertTrue(RelayPolicy.shouldAutoStart(RelayPolicy.ACTION_BOOT_COMPLETED, true, false));
        assertTrue(RelayPolicy.shouldAutoStart(RelayPolicy.ACTION_MY_PACKAGE_REPLACED, true, false));
        assertFalse(RelayPolicy.shouldAutoStart(RelayPolicy.ACTION_BOOT_COMPLETED, false, false));
        assertFalse(RelayPolicy.shouldAutoStart(RelayPolicy.ACTION_MY_PACKAGE_REPLACED, true, true));
        assertFalse(RelayPolicy.shouldAutoStart("android.intent.action.LOCKED_BOOT_COMPLETED", true, false));
        assertFalse(RelayPolicy.shouldAutoStart(null, true, false));
    }

    @Test
    public void wakeLockIsHeldOnlyWhileCycling() {
        assertTrue(RelayPolicy.wantsWakeLock(true, false, false, false));
        assertFalse("setting off", RelayPolicy.wantsWakeLock(false, false, false, false));
        assertFalse("paused: no timers run", RelayPolicy.wantsWakeLock(true, true, false, false));
        assertFalse("stopped", RelayPolicy.wantsWakeLock(true, false, true, false));
        assertFalse("destroyed", RelayPolicy.wantsWakeLock(true, false, false, true));
    }
}
