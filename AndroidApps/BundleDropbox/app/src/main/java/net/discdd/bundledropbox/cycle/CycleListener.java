package net.discdd.bundledropbox.cycle;

/**
 * Observer of {@code RoleCycleController} transitions. The service registers one to publish
 * {@code DropboxStatus} updates and to log role changes to the UI.
 *
 * <p>Called on the controller's single executor thread: implementations must be fast and must not
 * call back into the controller synchronously.
 */
public interface CycleListener {
    /** Value of {@code deadlineAtMillis} when the current mode has no deadline. */
    long NO_DEADLINE = -1L;

    /**
     * The cycle entered a new mode.
     *
     * @param previous            mode being left
     * @param current             mode entered
     * @param modeStartedAtMillis {@link Clock} time the mode was entered (monotonic)
     * @param deadlineAtMillis    {@link Clock} time the mode is scheduled to end: dwell expiry for
     *                            {@link CycleMode#HOSTING}/{@link CycleMode#VISITING}, drain-cap expiry for the
     *                            draining modes, retry time for {@link CycleMode#UNAVAILABLE}; otherwise
     *                            {@link #NO_DEADLINE}
     */
    void onModeChanged(CycleMode previous, CycleMode current, long modeStartedAtMillis, long deadlineAtMillis);

    /**
     * A radio operation failed (each attempt, including those that will be retried).
     *
     * @param mode   mode in which the failure happened
     * @param reason human-readable cause from {@link RadioCallback#failed(long, String)}
     */
    default void onCycleError(CycleMode mode, String reason) {
    }
}
