package net.discdd.bundledropbox.wifi;

/**
 * Pure-Java state machine for one VISIT attempt at one carrier, driven by {@link DropboxWifiJoiner}.
 *
 * <pre>
 *   CONNECTING --joined()--------------&gt; EXCHANGING --exchangeDone()--&gt; DONE
 *       |                                   |
 *       | failJoin(..) / radioLost()        | radioLost(): cancel the runner
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
 * <p>{@link #radioLost()} also covers losing the carrier's group mid-exchange (audit WD-3): the joiner treats
 * that like a lost radio, so the exchange is cancelled and ends through the same path.
 *
 * <p>Separately it tracks whether a connect may still be pending in the framework, so leaving sends
 * {@code cancelConnect} only when there is something to cancel (audit WD-1, {@link #needsCancelConnect()}).
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
    /** The connect request was sent (the attempt starts with it) and has not been resolved yet. */
    private boolean connectPending = true;

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
        connectPending = false;
        return true;
    }

    /**
     * The join failed (connect error, CONNECT_MS timeout, became GO, wrong GO).
     *
     * @param connectMayBePending true if the framework may still be negotiating (e.g. CONNECT_MS timeout);
     *                            false if the connect request was rejected or a group already formed (became
     *                            GO, wrong GO), where only {@code removeGroup} helps
     * @return true if the caller should log and leave the carrier; false if the attempt is no longer
     *         connecting (a joined attempt is never failed this way, so its runner is never orphaned)
     */
    boolean failJoin(boolean connectMayBePending) {
        if (stage != Stage.CONNECTING) return false;
        stage = Stage.DONE;
        connectPending = connectMayBePending;
        return true;
    }

    /**
     * @return true if leaving this attempt (failed join, stopVisit, radio lost while connecting) should send
     *         {@code cancelConnect}: the connect request was sent and neither rejected nor resolved into a
     *         group. False once joined: an exchanging attempt is left with {@code removeGroup} alone, because
     *         cancelConnect outside group formation is only answered BUSY and retried (audit WD-1)
     */
    boolean needsCancelConnect() {
        return connectPending;
    }

    /** Wi-Fi Direct disabled, channel lost, or (while exchanging) the carrier's group lost. */
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
