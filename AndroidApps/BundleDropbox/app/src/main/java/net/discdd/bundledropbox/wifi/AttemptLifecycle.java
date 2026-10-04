package net.discdd.bundledropbox.wifi;

/**
 * Pure-Java state machine for one VISIT attempt at one carrier, driven by {@link DropboxWifiJoiner}.
 *
 * <pre>
 *   CONNECTING --joined()--------------&gt; EXCHANGING --exchangeDone()--&gt; DONE
 *       |                                   |
 *       | failJoin() / radioLost()          | radioLost(): cancel the runner
 *       v                                   v
 *     DONE                              CANCELLING --exchangeDone()--&gt; DONE
 *
 *   end() (stopVisit / session superseded): any stage --&gt; DONE
 * </pre>
 *
 * <p>Invariant: once joined, an attempt ends only through {@link #exchangeDone()} (or {@link #end()} when
 * stopVisit takes over and waits for the runner itself). So every exchange that started produces exactly
 * one {@code visitFinished}, and no running {@link VisitRunner} is left behind. Before audit finding i1,
 * Wi-Fi Direct being disabled mid-exchange ended a joined attempt as a failed join: its {@code visitFinished}
 * was dropped and a later stopVisit no longer waited for the still-running exchange.
 *
 * <p>Main-thread use only (not synchronized).
 */
final class AttemptLifecycle {
    /** Where the attempt is. */
    enum Stage { CONNECTING, EXCHANGING, CANCELLING, DONE }

    /** What the joiner must do when the radio is lost (Wi-Fi Direct disabled, channel lost). */
    enum RadioLossAction {
        /** Still connecting: fail the attempt now instead of at its CONNECT_MS timeout. */
        FAIL_NOW,
        /** Exchanging: ask the runner to stop; its completion then reports and leaves the carrier. */
        CANCEL_EXCHANGE,
        /** Already cancelling or done: nothing to do. */
        NONE
    }

    private Stage stage = Stage.CONNECTING;

    Stage stage() {
        return stage;
    }

    boolean isDone() {
        return stage == Stage.DONE;
    }

    /** @return true once the carrier's group was joined (an exchange is or was running) */
    boolean isJoined() {
        return stage == Stage.EXCHANGING || stage == Stage.CANCELLING;
    }

    /**
     * The connection check said JOINED.
     *
     * @return true if this moved CONNECTING to EXCHANGING (the caller starts the runner); false if the
     *         attempt was already joined or finished (duplicate or late report)
     */
    boolean joined() {
        if (stage != Stage.CONNECTING) return false;
        stage = Stage.EXCHANGING;
        return true;
    }

    /**
     * The join failed (connect error, CONNECT_MS timeout, became GO, wrong GO).
     *
     * @return true if the caller should log and leave the carrier; false if the attempt is no longer
     *         connecting (a joined attempt is never failed this way, so its runner is never orphaned)
     */
    boolean failJoin() {
        if (stage != Stage.CONNECTING) return false;
        stage = Stage.DONE;
        return true;
    }

    /** Wi-Fi Direct disabled or channel lost while this attempt is live. */
    RadioLossAction radioLost() {
        switch (stage) {
            case CONNECTING:
                stage = Stage.DONE;
                return RadioLossAction.FAIL_NOW;
            case EXCHANGING:
                stage = Stage.CANCELLING;
                return RadioLossAction.CANCEL_EXCHANGE;
            default:
                return RadioLossAction.NONE;
        }
    }

    /**
     * The runner returned (or could not be started).
     *
     * @return true if the caller should report {@code visitFinished} and leave the carrier
     */
    boolean exchangeDone() {
        if (!isJoined()) return false;
        stage = Stage.DONE;
        return true;
    }

    /** The session ended (stopVisit or a newer startVisit); stopVisit handles any running runner. */
    void end() {
        stage = Stage.DONE;
    }
}
