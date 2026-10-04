package net.discdd.bundledropbox;

import net.discdd.utils.UserLogRepository;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single helper for operator-visible log lines: writes the message to JUL (the Logs tab and logcat) and
 * to android-core's {@code UserLogRepository} (the user-facing log). Shared by the service and the Wi-Fi
 * layer so the two no longer carry copies (audit m23).
 *
 * <p>Never throws: these lines are written from the role-cycle, Wi-Fi and visit threads, and a logging
 * failure must not abort a radio step or leak a gate token (audit M4, {@code log-handler-race-wedges-cycle}).
 */
public final class UserLog {
    private UserLog() {
    }

    /**
     * @param logger  the caller's JUL logger
     * @param type    user-log category (EXCHANGE, WIFI, ...)
     * @param level   severity for both logs
     * @param message plain-language message for the operator
     */
    public static void log(Logger logger, UserLogRepository.UserLogType type, Level level, String message) {
        try {
            logger.log(level, type + ": " + message);
            UserLogRepository.INSTANCE.log(new UserLogRepository.UserLogEntry(type, System.currentTimeMillis(),
                                                                              message, level));
        } catch (RuntimeException e) {
            System.err.println("DDD Dropbox: could not log '" + message + "': " + e);
        }
    }
}
