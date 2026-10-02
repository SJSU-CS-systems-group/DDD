package net.discdd.bundledropbox.wifi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses which carrier (BundleTransport) the dropbox joins next during a VISIT period.
 *
 * <p>Policy (02 Decisions: "join first discovered transport; rotate across cycles if several"):
 * <ol>
 *   <li>Only carriers seen within {@link #SEEN_TTL_MS} are candidates (DNS-SD answers go stale; the
 *       stock client also expires peers after 60 s, R3 §1a).</li>
 *   <li>A carrier is tried at most once per visit period, whether the attempt succeeded or failed. After
 *       a failed attempt the joiner keeps discovering <em>other</em> carriers until the period ends.</li>
 *   <li>Never-visited carriers first, then the least recently visited one, so several carriers are
 *       rotated across cycles; ties go to the carrier discovered first in this period.</li>
 * </ol>
 * Visit history survives {@link #beginPeriod()}; sightings and "attempted" marks do not.
 *
 * <p>Pure Java with injected times so the policy is unit-testable. Thread-safe (synchronized), although
 * the driver only calls it from the main looper.
 */
public final class CarrierSelector {
    /** A sighting older than this no longer makes a carrier a candidate. */
    public static final long SEEN_TTL_MS = 60_000L;

    /** Per-period sightings keyed by carrierId, in discovery order. */
    private final Map<String, Sighting> sightings = new LinkedHashMap<>();
    /** Visit history keyed by carrierId; kept across periods for least-recently-visited rotation. */
    private final Map<String, VisitRecord> history = new HashMap<>();

    /**
     * Starts a new visit period: forgets sightings and "attempted" marks, keeps visit history.
     */
    public synchronized void beginPeriod() {
        sightings.clear();
    }

    /**
     * Records a TXT sighting of an accepted carrier.
     *
     * @param carrierId            TXT {@code transportId}
     * @param deviceAddress        P2P MAC of the advertiser
     * @param deviceName           P2P device name
     * @param recencyBlobTimestamp server timestamp of its SUCCESS blob, 0 if none
     * @param nowMillis            monotonic now
     * @return true if this carrier was not yet known in the current period
     */
    public synchronized boolean observe(String carrierId,
                                        String deviceAddress,
                                        String deviceName,
                                        long recencyBlobTimestamp,
                                        long nowMillis) {
        Sighting s = sightings.get(carrierId);
        if (s == null) {
            sightings.put(carrierId, new Sighting(deviceAddress, deviceName, recencyBlobTimestamp, nowMillis));
            return true;
        }
        // Keep the latest address/name: a carrier may come back with a fresh randomized P2P MAC.
        if (deviceAddress != null && !deviceAddress.isBlank()) s.deviceAddress = deviceAddress;
        if (deviceName != null && !deviceName.isBlank()) s.deviceName = deviceName;
        s.recencyBlobTimestamp = Math.max(s.recencyBlobTimestamp, recencyBlobTimestamp);
        s.lastSeen = nowMillis;
        return false;
    }

    /**
     * Marks a carrier as tried in this period so {@link #next(long)} will not return it again.
     */
    public synchronized void markAttempted(String carrierId) {
        Sighting s = sightings.get(carrierId);
        if (s != null) s.attempted = true;
    }

    /**
     * Records the end of an exchange with a carrier (feeds least-recently-visited rotation).
     *
     * @param carrierId carrier id
     * @param nowMillis monotonic time the exchange ended
     * @param ok        whether it succeeded
     */
    public synchronized void recordVisit(String carrierId, long nowMillis, boolean ok) {
        history.put(carrierId, new VisitRecord(nowMillis, ok));
        markAttempted(carrierId);
    }

    /**
     * @param nowMillis monotonic now
     * @return the carrier to join next, or null if no untried, fresh candidate exists
     */
    public synchronized DiscoveredCarrier next(long nowMillis) {
        String bestId = null;
        for (Map.Entry<String, Sighting> e : sightings.entrySet()) {
            Sighting s = e.getValue();
            if (s.attempted || nowMillis - s.lastSeen > SEEN_TTL_MS) continue;
            if (s.deviceAddress == null || s.deviceAddress.isBlank()) continue;
            // FUTURE: prefer carriers advertising a newer recency blob (they synced with the server more
            // recently, so they likely hold fresher client-bound bundles); captured but unused in v1.
            // Strict "<" keeps the earliest-discovered carrier on ties (the map is in discovery order).
            if (bestId == null || lastVisited(e.getKey()) < lastVisited(bestId)) bestId = e.getKey();
        }
        return bestId == null ? null : toCarrier(bestId, sightings.get(bestId));
    }

    /**
     * @return all carriers seen in this period, in discovery order
     */
    public synchronized List<DiscoveredCarrier> snapshot() {
        List<DiscoveredCarrier> out = new ArrayList<>(sightings.size());
        sightings.forEach((id, s) -> out.add(toCarrier(id, s)));
        return List.copyOf(out);
    }

    /** @return monotonic time of the last exchange with the carrier, {@link DiscoveredCarrier#NEVER} if none */
    private long lastVisited(String carrierId) {
        VisitRecord v = history.get(carrierId);
        return v == null ? DiscoveredCarrier.NEVER : v.at;
    }

    private DiscoveredCarrier toCarrier(String id, Sighting s) {
        VisitRecord v = history.get(id);
        return new DiscoveredCarrier(id,
                                     s.deviceAddress,
                                     s.deviceName,
                                     s.recencyBlobTimestamp,
                                     s.firstSeen,
                                     s.lastSeen,
                                     v == null ? DiscoveredCarrier.NEVER : v.at,
                                     v != null && v.ok,
                                     s.attempted);
    }

    /** Mutable per-period sighting. */
    private static final class Sighting {
        String deviceAddress;
        String deviceName;
        long recencyBlobTimestamp;
        final long firstSeen;
        long lastSeen;
        boolean attempted;

        Sighting(String deviceAddress, String deviceName, long recencyBlobTimestamp, long now) {
            this.deviceAddress = deviceAddress;
            this.deviceName = deviceName;
            this.recencyBlobTimestamp = recencyBlobTimestamp;
            this.firstSeen = now;
            this.lastSeen = now;
        }
    }

    /** Immutable visit-history entry. */
    private static final class VisitRecord {
        final long at;
        final boolean ok;

        VisitRecord(long at, boolean ok) {
            this.at = at;
            this.ok = ok;
        }
    }
}
