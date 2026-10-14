package net.discdd.dropbox.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static net.discdd.dropbox.control.DropboxControlException.Kind.INVALID;
import static net.discdd.dropbox.control.DropboxControlException.Kind.MALFORMED;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNEXPECTED_TYPE;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNKNOWN_TYPE;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNSUPPORTED_VERSION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM tests of the recency channel's wire format: round trips, type dispatch and the rejection rules. */
class RecencyCodecTest {
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(new byte[91]);
    private static final String SIG_B64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7});
    private static final byte[] BLOB = {8, 1, 18, 4, 't', 'e', 's', 't', 0, -1};

    static RecencyRequest sampleRequest() {
        return new RecencyRequest(3, KEY_B64, SIG_B64);
    }

    static RecencyGrant sampleGrant() {
        return RecencyGrant.of(3, BLOB, 1_759_500_200_000L);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static DropboxControlException.Kind kindOf(byte[] bytes) {
        return assertThrows(DropboxControlException.class, () -> DropboxControlCodec.decode(bytes)).getKind();
    }

    private static DropboxControlException.Kind encodeKindOf(DropboxControlMessage m) {
        return assertThrows(DropboxControlException.class, () -> DropboxControlCodec.encode(m)).getKind();
    }

    @Test
    void appIdAndTypesAreTheContractValues() {
        assertEquals("net.discdd.bundledropbox.recency", DropboxControl.RECENCY_APP_ID);
        assertFalse(DropboxControl.RECENCY_APP_ID.equals(DropboxControl.APP_ID));
        assertEquals("recencyRequest", DropboxControl.TYPE_RECENCY_REQUEST);
        assertEquals("recencyGrant", DropboxControl.TYPE_RECENCY_GRANT);
    }

    @Test
    void requestRoundTrip() throws Exception {
        RecencyRequest in = sampleRequest();
        RecencyRequest out = DropboxControlCodec.decodeRecencyRequest(DropboxControlCodec.encode(in));
        assertEquals(in, out);
        assertEquals(in.hashCode(), out.hashCode());
        assertEquals(3, out.getRequestSeq());
        assertEquals(KEY_B64, out.getTlsPublicKey());
        assertEquals(SIG_B64, out.getSignature());
        assertArrayEquals(new byte[91], out.getTlsPublicKeyDer());
    }

    @Test
    void grantRoundTrip() throws Exception {
        RecencyGrant in = sampleGrant();
        RecencyGrant out = DropboxControlCodec.decodeRecencyGrant(DropboxControlCodec.encode(in));
        assertEquals(in, out);
        assertEquals(in.hashCode(), out.hashCode());
        assertEquals(3, out.getRequestSeq());
        assertArrayEquals(BLOB, out.getRecencyBlobBytes());
        assertEquals(1_759_500_200_000L, out.getIssuedAt());
    }

    @Test
    void encodedJsonHasTheContractFieldNames() throws Exception {
        String req = new String(DropboxControlCodec.encode(sampleRequest()), StandardCharsets.UTF_8);
        assertTrue(req.startsWith("{\"type\":\"recencyRequest\",\"v\":1,"), req);
        JsonObject r = JsonParser.parseString(req).getAsJsonObject();
        assertEquals(3, r.get("requestSeq").getAsLong());
        assertEquals(KEY_B64, r.get("tlsPublicKey").getAsString());
        assertEquals(SIG_B64, r.get("signature").getAsString());
        assertEquals(5, r.size());

        String grant = new String(DropboxControlCodec.encode(sampleGrant()), StandardCharsets.UTF_8);
        assertTrue(grant.startsWith("{\"type\":\"recencyGrant\",\"v\":1,"), grant);
        JsonObject g = JsonParser.parseString(grant).getAsJsonObject();
        assertEquals(Base64.getEncoder().encodeToString(BLOB), g.get("recencyBlob").getAsString());
        assertEquals(1_759_500_200_000L, g.get("issuedAt").getAsLong());

        byte[] noIssued = DropboxControlCodec.encode(RecencyGrant.of(1, BLOB, null));
        assertFalse(JsonParser.parseString(new String(noIssued, StandardCharsets.UTF_8)).getAsJsonObject()
                            .has("issuedAt"));
    }

    @Test
    void genericDecodeDispatchesOnType() throws Exception {
        assertInstanceOf(RecencyRequest.class, DropboxControlCodec.decode(DropboxControlCodec.encode(sampleRequest())));
        assertInstanceOf(RecencyGrant.class, DropboxControlCodec.decode(DropboxControlCodec.encode(sampleGrant())));
    }

    @Test
    void unknownFieldsAreIgnoredAndIssuedAtIsOptional() throws Exception {
        String blob = Base64.getEncoder().encodeToString(BLOB);
        RecencyGrant g = DropboxControlCodec.decodeRecencyGrant(utf8(
                "{\"type\":\"recencyGrant\",\"v\":1,\"requestSeq\":9,\"recencyBlob\":\"" + blob +
                        "\",\"future\":[1,{\"x\":2}]}"));
        assertEquals(9, g.getRequestSeq());
        assertNull(g.getIssuedAt());
    }

    @Test
    void wrongTypeForTypedDecodeIsUnexpected() throws Exception {
        byte[] req = DropboxControlCodec.encode(sampleRequest());
        byte[] grant = DropboxControlCodec.encode(sampleGrant());
        byte[] plan = DropboxControlCodec.encode(DropboxControlCodecTest.samplePlan());
        byte[] inv = DropboxControlCodec.encode(DropboxControlCodecTest.sampleInventory());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeRecencyRequest(grant)).getKind());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeRecencyGrant(req)).getKind());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeRecencyGrant(plan)).getKind());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeRecencyRequest(inv)).getKind());
        // A recency message on the plan/inventory queue is rejected too.
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodePlan(grant)).getKind());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeInventory(req)).getKind());
    }

    @Test
    void unknownTypeAndVersionAreRejected() {
        assertEquals(UNKNOWN_TYPE, kindOf(utf8("{\"type\":\"recencyRequestV2\",\"v\":1,\"requestSeq\":1}")));
        assertEquals(UNKNOWN_TYPE, kindOf(utf8("{\"type\":\"RecencyGrant\",\"v\":1,\"requestSeq\":1}")));
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":2,\"requestSeq\":1}")));
    }

    @Test
    void malformedFieldsAreRejected() {
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":1.5," +
                                                    "\"tlsPublicKey\":\"" + KEY_B64 + "\",\"signature\":\"AQ==\"}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":\"1\"," +
                                                    "\"tlsPublicKey\":\"" + KEY_B64 + "\",\"signature\":\"AQ==\"}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":1,\"requestSeq\":1," +
                                                    "\"recencyBlob\":\"AQ==\",\"issuedAt\":1.25}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":1,\"requestSeq\":1," +
                                                    "\"recencyBlob\":{\"a\":1}}")));
    }

    @Test
    void invalidRequestsAreRejectedOnDecodeAndEncode() {
        String ok = "\"tlsPublicKey\":\"" + KEY_B64 + "\",\"signature\":\"" + SIG_B64 + "\"";
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1," + ok + "}"))); // seq missing
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":0," + ok + "}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":-4," + ok + "}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":1," +
                                                  "\"signature\":\"" + SIG_B64 + "\"}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyRequest\",\"v\":1,\"requestSeq\":1," +
                                                  "\"tlsPublicKey\":\"" + KEY_B64 + "\"}")));

        assertEquals(INVALID, encodeKindOf(new RecencyRequest(0, KEY_B64, SIG_B64)));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, null, SIG_B64)));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, KEY_B64, "")));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, "not base64!", SIG_B64)));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, KEY_B64, "a b")));
        // The url alphabet is not accepted: the fields are standard Base64.
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, "ab-_", SIG_B64)));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, "=", SIG_B64)));
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, "A", SIG_B64))); // impossible length
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, KEY_B64 + "\n", SIG_B64)));

        String longKey = "A".repeat(DropboxControl.MAX_TLS_PUBLIC_KEY_CHARS + 4);
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, longKey, SIG_B64)));
        String longSig = "A".repeat(DropboxControl.MAX_SIGNATURE_CHARS + 4);
        assertEquals(INVALID, encodeKindOf(new RecencyRequest(1, KEY_B64, longSig)));
    }

    @Test
    void invalidGrantsAreRejectedOnDecodeAndEncode() {
        String blob = Base64.getEncoder().encodeToString(BLOB);
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":1,\"recencyBlob\":\"" + blob + "\"}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":1,\"requestSeq\":5}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"recencyGrant\",\"v\":1,\"requestSeq\":5," +
                                                  "\"recencyBlob\":\"" + blob + "\",\"issuedAt\":-1}")));

        assertEquals(INVALID, encodeKindOf(new RecencyGrant(0, blob, null)));
        assertEquals(INVALID, encodeKindOf(new RecencyGrant(1, "", null)));
        assertEquals(INVALID, encodeKindOf(new RecencyGrant(1, "%%%", null)));
        assertEquals(INVALID, encodeKindOf(RecencyGrant.of(1, new byte[0], null)));
        String longBlob = "A".repeat(DropboxControl.MAX_RECENCY_BLOB_CHARS + 4);
        assertEquals(INVALID, encodeKindOf(new RecencyGrant(1, longBlob, null)));
    }

    @Test
    void boundsFitRealValues() throws Exception {
        // The longest values the bounds allow still encode and decode.
        String key = "A".repeat(DropboxControl.MAX_TLS_PUBLIC_KEY_CHARS);
        String sig = "A".repeat(DropboxControl.MAX_SIGNATURE_CHARS);
        RecencyRequest r = new RecencyRequest(Long.MAX_VALUE, key, sig);
        assertEquals(r, DropboxControlCodec.decodeRecencyRequest(DropboxControlCodec.encode(r)));
        RecencyGrant g = new RecencyGrant(Long.MAX_VALUE, "A".repeat(DropboxControl.MAX_RECENCY_BLOB_CHARS), 0L);
        assertEquals(g, DropboxControlCodec.decodeRecencyGrant(DropboxControlCodec.encode(g)));
    }
}
