package net.discdd.bundledropbox.wifi;

import com.google.protobuf.InvalidProtocolBufferException;

import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire constants and pure-Java helpers for the dropbox's Wi-Fi Direct identity: the group it hosts and
 * the DNS-SD TXT records it publishes (HOST mode) or reads (VISIT mode).
 *
 * <p>Everything here must match what an <em>unmodified</em> BundleClient and BundleTransport expect, so the
 * constants are copied verbatim from {@code BundleTransport/.../wifi/DDDWifiServer.java} and
 * {@code BundleClient/.../service/wifiDirect/DDDWifiDirect.java} (Research/R1 §1, R3 §1a):
 * <ul>
 *   <li>instance {@code "ddd"}, service type {@code "_ddd._tcp"};</li>
 *   <li>group {@code "DIRECT-ddd_" + id}, passphrase {@code "ConnectToMe"};</li>
 *   <li>TXT keys {@code ddd}, {@code transportId}, {@code recencyBlob} (standard Base64 of the raw
 *       {@code GetRecencyBlobResponse} bytes).</li>
 * </ul>
 * The dropbox adds one key, {@code dropbox="1"}. Stock clients ignore unknown keys (they read only
 * {@code recencyBlob} and {@code transportId}), and a visiting dropbox uses it to skip other dropboxes
 * (Research/R2 §1.3 item 2).
 *
 * <p>No Android classes: unit-testable on the JVM.
 */
public final class DropboxTxt {
    /** DNS-SD instance name used by transports and requested by clients (R1 §1). */
    public static final String INSTANCE_NAME = "ddd";
    /** DNS-SD service type used by transports and requested by clients (R1 §1). */
    public static final String SERVICE_TYPE = "_ddd._tcp";
    /** Prefix of every DDD group name; Android also requires P2P group names to start with {@code DIRECT-xy}. */
    public static final String NETWORK_NAME_PREFIX = "DIRECT-ddd_";
    /** Fixed WPA2 passphrase used by every transport (DDDWifiServer.java:193). */
    public static final String PASSPHRASE = "ConnectToMe";

    /** TXT key the transport fills with its server URL; clients never read it (R3 §1a). */
    public static final String KEY_DDD = "ddd";
    /** TXT key holding the peer identity the client keys its RecentTransport rows on (R1 §1). */
    public static final String KEY_TRANSPORT_ID = "transportId";
    /** TXT key holding Base64(GetRecencyBlobResponse); clients ignore peers without it (R3 §1a). */
    public static final String KEY_RECENCY_BLOB = "recencyBlob";
    /** Dropbox-only TXT key; its presence marks "this peer is a dropbox, not a carrier". */
    public static final String KEY_DROPBOX = "dropbox";

    /** Value of {@link #KEY_DDD} for a dropbox. The dropbox is offline, so there is no server URL to publish. */
    public static final String DDD_VALUE_DROPBOX = "dropbox";
    // FUTURE: dropbox control endpoint / server destination manifests may need more TXT keys; bump this
    // version then so newer dropboxes can tell old ones apart.
    /** Value of {@link #KEY_DROPBOX}: the dropbox TXT schema version. */
    public static final String DROPBOX_VALUE = "1";

    /**
     * Limit of one DNS-SD TXT string ({@code key=value}), in bytes. The transport only checks the Base64
     * value against 255 (DDDWifiServer.java:284-289), but Android's {@code WifiP2pDnsSdServiceInfo}
     * rejects any {@code key=value} entry longer than 255 with an IllegalArgumentException, so we check
     * the whole entry. A real blob is about 204 Base64 chars (R1 §1), well inside either limit.
     */
    public static final int MAX_TXT_ENTRY_CHARS = 255;

    private DropboxTxt() {
    }

    /**
     * Group name for the dropbox's own group. Uses the stable {@code baseId}, not the rotating advertised
     * id, so persistent-group reuse keeps working across advertised-id rotations (03 Architecture §5).
     *
     * @param baseId 16-char id derived from the dropbox's TLS key, exactly like the transport's transportId
     * @return {@code "DIRECT-ddd_" + baseId}
     */
    public static String networkName(String baseId) {
        return NETWORK_NAME_PREFIX + baseId;
    }

    /**
     * Encodes a raw {@code GetRecencyBlobResponse} for the {@link #KEY_RECENCY_BLOB} TXT value.
     *
     * @param rawRecencyBlobResponse serialized response as stored by {@code RecencyBlobStore}, may be null
     * @return standard (non-URL) Base64, which is what the client decodes (DDDWifiDirect.java:189); or
     *         null if there is no blob or the TXT entry would exceed {@link #MAX_TXT_ENTRY_CHARS}
     */
    public static String encodeRecencyBlob(byte[] rawRecencyBlobResponse) {
        if (rawRecencyBlobResponse == null || rawRecencyBlobResponse.length == 0) return null;
        String encoded = Base64.getEncoder().encodeToString(rawRecencyBlobResponse);
        return fitsInTxt(KEY_RECENCY_BLOB, encoded) ? encoded : null;
    }

    /**
     * @return true if {@code key=value} fits in one DNS-SD TXT string
     */
    public static boolean fitsInTxt(String key, String value) {
        return key.length() + 1 + value.length() <= MAX_TXT_ENTRY_CHARS;
    }

    /**
     * Builds the TXT record the dropbox advertises in HOST mode.
     *
     * <p>If {@code encodedRecencyBlobOrNull} is null the {@code recencyBlob} key is omitted. That is
     * deliberate: a stock client ignores peers without the key (DDDWifiDirect.java:187), which is better
     * than being listed and then aborting every exchange with RecencyException (R3 §2). Normally the
     * controller does not host without a blob at all (bootstrap loops VISIT).
     *
     * @param advertisedId             the id clients see as {@code transportId} ({@code baseId-gen} when rotation
     *                                 is on); must be non-blank because the client NPEs on a missing id (R3 §1a)
     * @param encodedRecencyBlobOrNull result of {@link #encodeRecencyBlob(byte[])}
     * @return an unmodifiable, insertion-ordered map
     * @throws IllegalArgumentException if {@code advertisedId} is blank or too long for a TXT entry
     */
    public static Map<String, String> hostRecord(String advertisedId, String encodedRecencyBlobOrNull) {
        if (advertisedId == null || advertisedId.isBlank()) {
            throw new IllegalArgumentException("advertised transportId must not be blank");
        }
        if (!fitsInTxt(KEY_TRANSPORT_ID, advertisedId)) {
            throw new IllegalArgumentException("advertised transportId too long for a TXT entry: " + advertisedId);
        }
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put(KEY_DDD, DDD_VALUE_DROPBOX);
        txt.put(KEY_TRANSPORT_ID, advertisedId);
        if (encodedRecencyBlobOrNull != null) txt.put(KEY_RECENCY_BLOB, encodedRecencyBlobOrNull);
        txt.put(KEY_DROPBOX, DROPBOX_VALUE);
        return Collections.unmodifiableMap(txt);
    }

    /**
     * Decides whether a TXT record seen during VISIT discovery belongs to a carrier (a BundleTransport the
     * dropbox should join).
     *
     * <p>Accepted iff: {@code recencyBlob} present and parseable (the same filter the stock client applies,
     * DDDWifiDirect.java:187-194), {@code transportId} present and non-blank (it is the carrier id the
     * {@code ForwardLedger} is keyed on), and <b>no</b> {@code dropbox} key (dropboxes never visit each
     * other; R2 §1.3). The blob's status is not required to be SUCCESS: a carrier that has never reached
     * the server still accepts uploads, it just cannot give us a recency blob.
     *
     * @param txt TXT map from {@code DnsSdTxtRecordListener}, may be null
     * @return an accepted {@link CarrierAd}, or a rejected one carrying the reason
     */
    public static CarrierAd parseCarrier(Map<String, String> txt) {
        if (txt == null || txt.isEmpty()) return CarrierAd.rejected("empty TXT record");
        if (txt.containsKey(KEY_DROPBOX)) return CarrierAd.rejected("peer is a dropbox");
        String encodedBlob = txt.get(KEY_RECENCY_BLOB);
        if (encodedBlob == null) return CarrierAd.rejected("no recencyBlob");
        String transportId = txt.get(KEY_TRANSPORT_ID);
        if (transportId == null || transportId.isBlank()) return CarrierAd.rejected("no transportId");
        GetRecencyBlobResponse response;
        try {
            response = GetRecencyBlobResponse.parseFrom(Base64.getDecoder().decode(encodedBlob));
        } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
            return CarrierAd.rejected("unparseable recencyBlob: " + e.getMessage());
        }
        long blobTimestamp = response.getStatus() == RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS ?
                             response.getRecencyBlob().getBlobTimestamp() :
                             0L;
        return CarrierAd.accepted(transportId, blobTimestamp);
    }

    /**
     * Result of {@link #parseCarrier(Map)}: either an accepted carrier advertisement or a rejection reason.
     */
    public static final class CarrierAd {
        private final String transportId;
        private final long recencyBlobTimestamp;
        private final String rejection;

        private CarrierAd(String transportId, long recencyBlobTimestamp, String rejection) {
            this.transportId = transportId;
            this.recencyBlobTimestamp = recencyBlobTimestamp;
            this.rejection = rejection;
        }

        static CarrierAd accepted(String transportId, long recencyBlobTimestamp) {
            return new CarrierAd(transportId, recencyBlobTimestamp, null);
        }

        static CarrierAd rejected(String reason) {
            return new CarrierAd(null, 0L, reason);
        }

        /** @return true if the peer is a carrier the dropbox may join */
        public boolean isAccepted() { return rejection == null; }

        /** @return the carrier's TXT {@code transportId} (the dropbox's carrierId), null if rejected */
        public String transportId() { return transportId; }

        /** @return server timestamp of the advertised blob if its status is SUCCESS, else 0 */
        public long recencyBlobTimestamp() { return recencyBlobTimestamp; }

        /** @return why the peer was rejected, null if accepted */
        public String rejection() { return rejection; }

        @Override
        public String toString() {
            return isAccepted() ? "CarrierAd{" + transportId + ", blobTs=" + recencyBlobTimestamp + "}" :
                   "CarrierAd{rejected: " + rejection + "}";
        }
    }
}
