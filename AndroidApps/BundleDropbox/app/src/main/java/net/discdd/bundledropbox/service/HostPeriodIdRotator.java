package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.cycle.CycleListener;
import net.discdd.bundledropbox.cycle.CycleMode;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Rotates the advertised transport id at the start of every hosting period (audit M8,
 * {@code rotation-only-after-visit-gates-clients}).
 *
 * <p><b>Why.</b> A stock BundleClient exchanges with a transport only when the TXT {@code blobTimestamp} is
 * newer than its {@code lastExchange} for that {@code transportId}, and records {@code lastExchange} after every
 * attempt, even a failed one (Research/R3 §1b). The dropbox re-serves the carrier's blob unchanged, so with one
 * id per carrier visit a client got at most one exchange per carrier visit: mail written after that exchange
 * waited a whole extra carrier round, and every lockout (a client refused while draining) lasted until the
 * next successful visit. The previous rule bumped the id only after a visit that fetched a SUCCESS blob, so a
 * VISIT period that found no carrier re-advertised the same id.
 *
 * <p><b>Rule.</b> With {@code rotate_advertised_id} on, the generation goes up each time the cycle enters
 * {@link CycleMode#HOST_STARTING} from another mode, so every client can exchange once per hosting period.
 * Backoff retries of the same start stay in HOST_STARTING and do not bump again. The controller notifies
 * listeners on its own thread before it issues {@code startHosting} ({@code RoleCycleController.issueOp}), so
 * the new id is in place before the Wi-Fi host reads it to build the TXT record. The generation is persisted
 * ({@link DropboxIdentity#bumpGeneration()}), so an id is never reused after a restart.
 *
 * <p><b>Cost.</b> About one new id per client period (~1,000 a day at the 60 s/30 s defaults). Old ids drop off
 * a client's peer list after 60 s unseen (R3); a growing Room table of past ids on the client is not verified.
 *
 * <p>Register this listener before the one that publishes status, so the Status tab shows the new id.
 */
public final class HostPeriodIdRotator implements CycleListener {
    private final DropboxIdentity identity;
    private final Consumer<String> rotated;

    /**
     * @param identity the dropbox identity (its {@code rotate} flag follows the setting)
     * @param rotated  told the new advertised id after each bump (logging)
     */
    public HostPeriodIdRotator(DropboxIdentity identity, Consumer<String> rotated) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rotated = Objects.requireNonNull(rotated, "rotated");
    }

    @Override
    public void onModeChanged(CycleMode previous, CycleMode current, long modeStartedAtMillis, long deadlineAtMillis) {
        if (current != CycleMode.HOST_STARTING || previous == CycleMode.HOST_STARTING) return;
        if (!identity.isRotating()) return;
        rotated.accept(identity.bumpGeneration());
    }
}
