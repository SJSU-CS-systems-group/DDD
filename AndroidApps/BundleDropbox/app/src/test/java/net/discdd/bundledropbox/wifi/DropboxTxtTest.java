package net.discdd.bundledropbox.wifi;

import com.google.protobuf.ByteString;

import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;

import org.junit.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link DropboxTxt}: the TXT records must stay byte-compatible with what the unmodified
 * client and transport read and write (Research/R1 §1, R3 §1a).
 */
public class DropboxTxtTest {
    /** A realistic server-signed blob: 33-byte key, 64-byte signature, SHA-1 senderId (R1 §1: ~204 chars). */
    private static GetRecencyBlobResponse realisticBlob(long timestamp) {
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(RecencyBlob.newBuilder()
                                        .setVersion(0)
                                        .setNonce(123456789)
                                        .setBlobTimestamp(timestamp)
                                        .setSenderId("abcdefghijklmnopqrstuvwxyz0"))
                .setRecencyBlobSignature(ByteString.copyFrom(new byte[64]))
                .setServerPublicKey(ByteString.copyFrom(new byte[33]))
                .build();
    }

    @Test
    public void constantsMatchTransport() {
        assertEquals("ddd", DropboxTxt.INSTANCE_NAME);
        assertEquals("_ddd._tcp", DropboxTxt.SERVICE_TYPE);
        assertEquals("DIRECT-ddd_abcdefghijklmnop", DropboxTxt.networkName("abcdefghijklmnop"));
        assertEquals("ConnectToMe", DropboxTxt.PASSPHRASE);
    }

    @Test
    public void hostRecordHasAllKeysInOrder() {
        String encoded = DropboxTxt.encodeRecencyBlob(realisticBlob(1_700_000_000_000L).toByteArray());
        Map<String, String> txt = DropboxTxt.hostRecord("base-3", encoded);
        assertEquals(List.of("ddd", "transportId", "recencyBlob", "dropbox"), List.copyOf(txt.keySet()));
        assertEquals("dropbox", txt.get("ddd"));
        assertEquals("base-3", txt.get("transportId"));
        assertEquals("1", txt.get("dropbox"));
        assertEquals(encoded, txt.get("recencyBlob"));
    }

    @Test
    public void realisticBlobFitsAndRoundTripsWithClientDecoder() throws Exception {
        GetRecencyBlobResponse blob = realisticBlob(1_700_000_000_000L);
        String encoded = DropboxTxt.encodeRecencyBlob(blob.toByteArray());
        assertTrue(encoded.length() < 255);
        // Exactly what DDDWifiDirect does with the TXT value (java.util.Base64 standard decoder).
        assertEquals(blob, GetRecencyBlobResponse.parseFrom(Base64.getDecoder().decode(encoded)));
    }

    @Test
    public void missingBlobOmitsKeyInsteadOfCrashing() {
        assertNull(DropboxTxt.encodeRecencyBlob(null));
        assertNull(DropboxTxt.encodeRecencyBlob(new byte[0]));
        Map<String, String> txt = DropboxTxt.hostRecord("base", null);
        assertFalse(txt.containsKey("recencyBlob"));
        assertTrue(txt.containsKey("transportId"));
    }

    @Test
    public void oversizedBlobIsRefused() {
        // 255 - "recencyBlob=".length() = 243 chars max; 183 raw bytes encode to 244 chars.
        assertNull(DropboxTxt.encodeRecencyBlob(new byte[183]));
        assertEquals(240, DropboxTxt.encodeRecencyBlob(new byte[180]).length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankAdvertisedIdRejected() {
        DropboxTxt.hostRecord(" ", null);
    }

    @Test
    public void acceptsTransportRecord() {
        Map<String, String> txt = new HashMap<>();
        txt.put("ddd", "ravlykmail.com:7778");
        txt.put("transportId", "T1234567890abcde");
        txt.put("recencyBlob", Base64.getEncoder().encodeToString(realisticBlob(42L).toByteArray()));
        DropboxTxt.CarrierAd ad = DropboxTxt.parseCarrier(txt);
        assertTrue(ad.toString(), ad.isAccepted());
        assertEquals("T1234567890abcde", ad.transportId());
        assertEquals(42L, ad.recencyBlobTimestamp());
    }

    @Test
    public void acceptsCarrierThatNeverReachedServerButReportsNoTimestamp() {
        // A transport that never synced advertises the default instance (status UNDEFINED, R1 §3.1).
        Map<String, String> txt = new HashMap<>();
        txt.put("transportId", "T1");
        txt.put("recencyBlob", Base64.getEncoder().encodeToString(GetRecencyBlobResponse.getDefaultInstance().toByteArray()));
        DropboxTxt.CarrierAd ad = DropboxTxt.parseCarrier(txt);
        assertTrue(ad.isAccepted());
        assertEquals(0L, ad.recencyBlobTimestamp());
    }

    @Test
    public void rejectsOtherDropboxes() {
        Map<String, String> own = DropboxTxt.hostRecord("D1",
                                                        DropboxTxt.encodeRecencyBlob(realisticBlob(1L).toByteArray()));
        DropboxTxt.CarrierAd ad = DropboxTxt.parseCarrier(own);
        assertFalse(ad.isAccepted());
        assertEquals("peer is a dropbox", ad.rejection());
    }

    @Test
    public void rejectsMissingOrBadFields() {
        assertFalse(DropboxTxt.parseCarrier(null).isAccepted());
        assertFalse(DropboxTxt.parseCarrier(Map.of()).isAccepted());
        assertFalse(DropboxTxt.parseCarrier(Map.of("transportId", "T1")).isAccepted());
        String blob = Base64.getEncoder().encodeToString(realisticBlob(1L).toByteArray());
        assertFalse(DropboxTxt.parseCarrier(Map.of("recencyBlob", blob)).isAccepted());
        assertFalse(DropboxTxt.parseCarrier(Map.of("recencyBlob", blob, "transportId", "")).isAccepted());
        assertFalse(DropboxTxt.parseCarrier(Map.of("recencyBlob", "%%%not-base64", "transportId", "T1")).isAccepted());
        // Valid Base64 but not a protobuf message (0xFF bytes are an invalid tag).
        String garbage = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertFalse(DropboxTxt.parseCarrier(Map.of("recencyBlob", garbage, "transportId", "T1")).isAccepted());
    }
}
