package net.discdd.dropbox.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.discdd.dropbox.control.DropboxControlException.Kind.INVALID;
import static net.discdd.dropbox.control.DropboxControlException.Kind.MALFORMED;
import static net.discdd.dropbox.control.DropboxControlException.Kind.TOO_LARGE;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNEXPECTED_TYPE;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNKNOWN_TYPE;
import static net.discdd.dropbox.control.DropboxControlException.Kind.UNSUPPORTED_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM tests of the control ADU wire format: round trips, forward compatibility and the rejection rules. */
class DropboxControlCodecTest {
    private static final String ID_A = "AbC-_123=";
    private static final String ID_B = "zzz_999";
    private static final String KEY = "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c";

    static InventoryReport sampleInventory() {
        return new InventoryReport(42, 6, 1_000_000L, 4_096L, 1_759_500_000_000L,
                                   List.of(new InventoryReport.UpstreamEntry(ID_A, 1234, KEY),
                                           new InventoryReport.UpstreamEntry(ID_B, 10, null)),
                                   List.of(new InventoryReport.DownstreamEntry(ID_B, 5678, KEY, 3,
                                                                               1_759_400_000_000L),
                                           new InventoryReport.DownstreamEntry(ID_A, 1, null, 0, null)),
                                   List.of(new InventoryReport.PlanResult(6, List.of(ID_A), List.of(
                                           new InventoryReport.SkippedEntry(ID_B,
                                                                            DropboxControl.SkipReason.NOT_FOUND)))));
    }

    static RetentionPlan samplePlan() {
        return new RetentionPlan(7, 42, 1_759_500_100_000L,
                                 List.of(new RetentionPlan.Deletion(ID_A, DropboxControl.Reason.RECEIVED_BY_SERVER),
                                         new RetentionPlan.Deletion(ID_B, DropboxControl.Reason.SUPERSEDED)),
                                 List.of(new RetentionPlan.Deletion(ID_B, DropboxControl.Reason.CLIENT_ACKED),
                                         new RetentionPlan.Deletion(ID_A, DropboxControl.Reason.EXPIRED)),
                                 List.of("keepMe1"));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static DropboxControlException.Kind kindOf(byte[] bytes) {
        return assertThrows(DropboxControlException.class, () -> DropboxControlCodec.decode(bytes)).getKind();
    }

    @Test
    void inventoryRoundTrip() throws Exception {
        InventoryReport in = sampleInventory();
        byte[] bytes = DropboxControlCodec.encode(in);
        InventoryReport out = DropboxControlCodec.decodeInventory(bytes);
        assertEquals(in, out);
        assertEquals(in.hashCode(), out.hashCode());
        assertEquals(42, out.getReportSeq());
        assertEquals(KEY, out.getUpstream().get(0).getClientKeyB64());
        assertNull(out.getUpstream().get(1).getClientKeyB64());
        assertNull(out.getDownstream().get(1).getLastServedAt());
        assertEquals(DropboxControl.SkipReason.NOT_FOUND, out.getPlanResults().get(0).getSkipped().get(0).getReason());
    }

    @Test
    void planRoundTrip() throws Exception {
        RetentionPlan in = samplePlan();
        RetentionPlan out = DropboxControlCodec.decodePlan(DropboxControlCodec.encode(in));
        assertEquals(in, out);
        assertEquals(List.of("keepMe1"), out.getKeep());
        assertEquals(DropboxControl.Reason.EXPIRED, out.getDeleteDownstream().get(1).getReason());
    }

    @Test
    void encodedJsonHasTypeAndVersionFirstAndOmitsNulls() throws Exception {
        String json = new String(DropboxControlCodec.encode(samplePlan()), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"type\":\"plan\",\"v\":1,"), json);

        RetentionPlan noKeep = new RetentionPlan(1, 0, null, null, null, null);
        String minimal = new String(DropboxControlCodec.encode(noKeep), StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(minimal).getAsJsonObject();
        assertFalse(o.has("keep"));
        assertFalse(o.has("createdAt"));
        assertTrue(o.getAsJsonArray("deleteUpstream").isEmpty());
    }

    @Test
    void minimalInventoryDecodesWithEmptyLists() throws Exception {
        InventoryReport r = DropboxControlCodec.decodeInventory(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1}"));
        assertEquals(1, r.getReportSeq());
        assertEquals(0, r.getLastAppliedPlanSeq());
        assertTrue(r.getUpstream().isEmpty());
        assertTrue(r.getDownstream().isEmpty());
        assertTrue(r.getPlanResults().isEmpty());
        assertNull(r.getCreatedAt());
    }

    @Test
    void explicitNullListsDecodeAsEmpty() throws Exception {
        RetentionPlan p = DropboxControlCodec.decodePlan(
                utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":3,\"deleteUpstream\":null,\"keep\":null}"));
        assertTrue(p.getDeleteUpstream().isEmpty());
        assertTrue(p.getKeep().isEmpty());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String json = "{\"type\":\"plan\",\"v\":1,\"planSeq\":9,\"futureField\":{\"nested\":[1,2,{\"x\":null}]}," +
                "\"deleteUpstream\":[{\"bundleId\":\"" + ID_A + "\",\"reason\":\"SUPERSEDED\",\"extra\":true}]}";
        RetentionPlan p = DropboxControlCodec.decodePlan(utf8(json));
        assertEquals(9, p.getPlanSeq());
        assertEquals(ID_A, p.getDeleteUpstream().get(0).getBundleId());
    }

    @Test
    void unknownReasonDecodesAsNullButKeepsTheDeletion() throws Exception {
        String json = "{\"type\":\"plan\",\"v\":1,\"planSeq\":9,\"deleteDownstream\":[{\"bundleId\":\"" + ID_A +
                "\",\"reason\":\"SOME_FUTURE_REASON\"}]}";
        RetentionPlan p = DropboxControlCodec.decodePlan(utf8(json));
        assertEquals(1, p.getDeleteDownstream().size());
        assertNull(p.getDeleteDownstream().get(0).getReason());
    }

    @Test
    void unknownOrMissingTypeIsRejected() {
        assertEquals(UNKNOWN_TYPE, kindOf(utf8("{\"type\":\"directive\",\"v\":1}")));
        assertEquals(UNKNOWN_TYPE, kindOf(utf8("{\"v\":1,\"planSeq\":1}")));
        assertEquals(UNKNOWN_TYPE, kindOf(utf8("{\"type\":7,\"v\":1}")));
    }

    @Test
    void unsupportedOrMissingVersionIsRejected() {
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"plan\",\"v\":2,\"planSeq\":1}")));
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"plan\",\"planSeq\":1}")));
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"plan\",\"v\":\"1\",\"planSeq\":1}")));
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"plan\",\"v\":1.5,\"planSeq\":1}")));
        // A future version is reported as such even when its type is new to us.
        assertEquals(UNSUPPORTED_VERSION, kindOf(utf8("{\"type\":\"newthing\",\"v\":2}")));
    }

    @Test
    void malformedInputIsRejected() {
        assertEquals(MALFORMED, kindOf(utf8("")));
        assertEquals(MALFORMED, kindOf(utf8("not json at all {")));
        assertEquals(MALFORMED, kindOf(utf8("[1,2,3]")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":1} trailing")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":\"abc\"}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"deleteUpstream\":{}}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1.5}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":99999999999999999999}")));
        // Numbers must be exact integers: Gson would otherwise accept a numeric string or truncate 2.0e0.
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":\"5\"}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":2.0e0}")));
        assertEquals(MALFORMED, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                    "\"upstream\":[{\"bundleId\":\"a\",\"sizeBytes\":1.25}]}")));
        assertEquals(MALFORMED, assertThrows(DropboxControlException.class,
                                             () -> DropboxControlCodec.decode((byte[]) null)).getKind());
    }

    @Test
    void deeplyNestedUnknownFieldIsRejectedOrIgnoredWithoutCrashing() {
        int depth = 100_000;
        String json = "{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"x\":" + "[".repeat(depth) + "]".repeat(depth) + "}";
        try {
            DropboxControlCodec.decode(utf8(json));
        } catch (DropboxControlException e) {
            assertEquals(MALFORMED, e.getKind());
        }
    }

    @Test
    void invalidValuesAreRejected() {
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"plan\",\"v\":1}"))); // planSeq missing -> 0
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":0}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1,\"usedBytes\":-1}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                  "\"upstream\":[{\"bundleId\":\"../../etc/passwd\",\"sizeBytes\":1}]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                  "\"upstream\":[{\"sizeBytes\":1}]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                  "\"upstream\":[null]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                  "\"downstream\":[{\"bundleId\":\"a\",\"clientKeyB64\":\"not base64!\"}]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"keep\":[\"a/b\"]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"deleteDownstream\":[{}]}")));
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"inventory\",\"v\":1,\"reportSeq\":1," +
                                                  "\"planResults\":[{\"planSeq\":1,\"deleted\":[\"x.y\"]}]}")));
        String longId = "a".repeat(101);
        assertEquals(INVALID, kindOf(utf8("{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"keep\":[\"" + longId + "\"]}")));
    }

    @Test
    void encodeValidatesToo() {
        InventoryReport bad = new InventoryReport(0, 0, 0, 0, null, null, null, null);
        assertEquals(INVALID, assertThrows(DropboxControlException.class,
                                           () -> DropboxControlCodec.encode(bad)).getKind());
        RetentionPlan badId = new RetentionPlan(1, 0, null,
                                                List.of(new RetentionPlan.Deletion("bad id", null)), null, null);
        assertEquals(INVALID, assertThrows(DropboxControlException.class,
                                           () -> DropboxControlCodec.encode(badId)).getKind());
    }

    @Test
    void wrongTypeForTypedDecodeIsUnexpected() throws Exception {
        byte[] plan = DropboxControlCodec.encode(samplePlan());
        byte[] inv = DropboxControlCodec.encode(sampleInventory());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodeInventory(plan)).getKind());
        assertEquals(UNEXPECTED_TYPE, assertThrows(DropboxControlException.class,
                                                   () -> DropboxControlCodec.decodePlan(inv)).getKind());
        assertInstanceOf(RetentionPlan.class, DropboxControlCodec.decode(plan));
        assertInstanceOf(InventoryReport.class, DropboxControlCodec.decode(inv));
    }

    @Test
    void oversizedInputIsRejectedBeforeParsing() {
        byte[] big = new byte[DropboxControl.MAX_MESSAGE_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        assertEquals(TOO_LARGE, kindOf(big));
    }

    @Test
    void oversizedStreamIsRejectedWithoutReadingItAll() {
        // An endless stream: decode(InputStream) must stop once the bound is passed.
        InputStream endless = new InputStream() {
            @Override
            public int read() {return ' ';}

            @Override
            public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) ' ');
                return len;
            }
        };
        assertEquals(TOO_LARGE, assertThrows(DropboxControlException.class,
                                             () -> DropboxControlCodec.decode(endless)).getKind());
    }

    @Test
    void streamDecodeMatchesByteDecode() throws Exception {
        byte[] bytes = DropboxControlCodec.encode(sampleInventory());
        assertEquals(sampleInventory(), DropboxControlCodec.decode(new ByteArrayInputStream(bytes)));
    }

    @Test
    void tooManyEntriesIsRejected() {
        List<String> ids = new ArrayList<>(Collections.nCopies(DropboxControl.MAX_ENTRIES_PER_LIST + 1, "a"));
        RetentionPlan tooMany = new RetentionPlan(1, 0, null, null, null, ids);
        assertEquals(TOO_LARGE, assertThrows(DropboxControlException.class,
                                             () -> DropboxControlCodec.encode(tooMany)).getKind());

        StringBuilder json = new StringBuilder("{\"type\":\"plan\",\"v\":1,\"planSeq\":1,\"keep\":[");
        for (int i = 0; i <= DropboxControl.MAX_ENTRIES_PER_LIST; i++) json.append(i == 0 ? "" : ",").append("\"a\"");
        json.append("]}");
        assertEquals(TOO_LARGE, kindOf(utf8(json.toString())));

        List<InventoryReport.PlanResult> results = new ArrayList<>();
        for (int i = 0; i <= DropboxControl.MAX_PLAN_RESULTS; i++) results.add(new InventoryReport.PlanResult(i + 1, null, null));
        InventoryReport inv = new InventoryReport(1, 0, 0, 0, null, null, null, results);
        assertEquals(TOO_LARGE, assertThrows(DropboxControlException.class,
                                             () -> DropboxControlCodec.encode(inv)).getKind());
    }

    @Test
    void maxEntriesOfRealisticIdsFitInTheByteBound() throws Exception {
        // 20,000 upstream entries with 100-char IDs and fingerprints must still encode under 4 MiB.
        String id = "A".repeat(100);
        List<InventoryReport.UpstreamEntry> up = new ArrayList<>();
        for (int i = 0; i < DropboxControl.MAX_ENTRIES_PER_LIST; i++) {
            up.add(new InventoryReport.UpstreamEntry(id, 10_000_000L, KEY));
        }
        byte[] bytes = DropboxControlCodec.encode(new InventoryReport(1, 0, 0, 0, null, up, null, null));
        assertTrue(bytes.length <= DropboxControl.MAX_MESSAGE_BYTES);
        assertEquals(DropboxControl.MAX_ENTRIES_PER_LIST, DropboxControlCodec.decodeInventory(bytes).getUpstream().size());
    }

    @Test
    void gettersAreUnmodifiableAndConstructorCopies() throws Exception {
        List<String> keep = new ArrayList<>(List.of("a"));
        RetentionPlan p = new RetentionPlan(1, 0, null, null, null, keep);
        keep.add("b");
        assertEquals(List.of("a"), p.getKeep());
        assertThrows(UnsupportedOperationException.class, () -> p.getKeep().add("c"));
        assertThrows(UnsupportedOperationException.class,
                     () -> DropboxControlCodec.decodePlan(DropboxControlCodec.encode(p)).getDeleteUpstream().add(null));
    }
}
