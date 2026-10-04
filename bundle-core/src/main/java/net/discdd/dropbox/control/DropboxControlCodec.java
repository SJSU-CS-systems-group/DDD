package net.discdd.dropbox.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Encodes and decodes dropbox control ADUs: one UTF-8 JSON object per ADU, the same style as BundleClient's
 * server messages (Gson, Research/R6 §1.1), with a {@code "type"} discriminator and a {@code "v"} version:
 *
 * <pre>{"type":"inventory","v":1,"reportSeq":42,...}
 * {"type":"plan","v":1,"planSeq":7,...}</pre>
 *
 * <p>Rules, applied the same way by the server and the dropbox:
 * <ul>
 *   <li>Size: at most {@link DropboxControl#MAX_MESSAGE_BYTES} encoded bytes and
 *       {@link DropboxControl#MAX_ENTRIES_PER_LIST} entries per list, checked on encode and decode.</li>
 *   <li>Unknown fields are ignored, so a newer peer may add optional fields without a version bump.</li>
 *   <li>An unknown {@code type} or any {@code v} other than {@link DropboxControl#VERSION} is rejected with a
 *       typed {@link DropboxControlException}; receivers log and drop it.</li>
 *   <li>Every decoded message is validated ({@code INVALID} on bad bundle IDs, negative sizes, missing
 *       sequence numbers), and so is every message before it is encoded.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe (Gson instances are thread-safe). Pure Java; must stay within the Android API 33
 * surface that animal-sniffer enforces for bundle-core.
 */
public final class DropboxControlCodec {
    static final String FIELD_TYPE = "type";
    static final String FIELD_VERSION = "v";

    /**
     * Gson's default {@code long} handling silently truncates {@code 1.5} to 1 and wraps integers beyond 64 bits.
     * Sequence numbers and sizes drive deletions, so they must be exact: accept only JSON integers that fit in a
     * {@code long}, and reject everything else (including numeric strings) as malformed.
     */
    private static final TypeAdapter<Long> STRICT_LONG = new TypeAdapter<Long>() {
        @Override
        public void write(JsonWriter out, Long value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.longValue());
        }

        @Override
        public Long read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null; // a primitive field keeps its default (0), which validation then judges
            }
            if (token != JsonToken.NUMBER) throw new JsonSyntaxException("expected an integer, got " + token);
            String text = in.nextString();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new JsonSyntaxException("not a 64-bit integer: " + text, e);
            }
        }
    };

    /** Unknown fields are skipped by Gson's reflective adapter by default; nulls are omitted on output. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(long.class, STRICT_LONG)
            .registerTypeAdapter(Long.class, STRICT_LONG)
            .create();

    private DropboxControlCodec() {}

    /**
     * Encodes a message as UTF-8 JSON with {@code "type"} and {@code "v"} first.
     *
     * @param message an {@link InventoryReport} or {@link RetentionPlan}
     * @return the ADU bytes
     * @throws DropboxControlException {@code INVALID} if the message breaks the contract, {@code TOO_LARGE} if a
     *                                 list or the encoded result exceeds the bounds
     */
    public static byte[] encode(DropboxControlMessage message) throws DropboxControlException {
        if (message == null) throw new IllegalArgumentException("message is null");
        message.validate();
        JsonObject out = new JsonObject();
        out.addProperty(FIELD_TYPE, message.type());
        out.addProperty(FIELD_VERSION, DropboxControl.VERSION);
        for (Map.Entry<String, JsonElement> e : GSON.toJsonTree(message).getAsJsonObject().entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        byte[] bytes = GSON.toJson(out).getBytes(StandardCharsets.UTF_8);
        requireSize(bytes.length);
        return bytes;
    }

    /**
     * Decodes and validates one control ADU.
     *
     * @param bytes the ADU content
     * @return an {@link InventoryReport} or a {@link RetentionPlan}
     * @throws DropboxControlException typed by {@link DropboxControlException.Kind}
     */
    public static DropboxControlMessage decode(byte[] bytes) throws DropboxControlException {
        if (bytes == null) throw new DropboxControlException(DropboxControlException.Kind.MALFORMED, "null input");
        requireSize(bytes.length);

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new DropboxControlException(DropboxControlException.Kind.MALFORMED, "not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new DropboxControlException(DropboxControlException.Kind.MALFORMED, "unparseable JSON", e);
        } catch (StackOverflowError e) {
            // Defensive: the input is untrusted and a pathologically nested document must not crash the caller.
            throw new DropboxControlException(DropboxControlException.Kind.MALFORMED, "JSON nested too deeply");
        }

        // Check the version before the type, so a future v2 with new types reports UNSUPPORTED_VERSION.
        int version = readVersion(root);
        if (version != DropboxControl.VERSION) {
            throw new DropboxControlException(DropboxControlException.Kind.UNSUPPORTED_VERSION,
                                              "v=" + version + ", supported v=" + DropboxControl.VERSION);
        }

        Class<? extends DropboxControlMessage> clazz = classFor(readType(root));
        DropboxControlMessage message;
        try {
            message = GSON.fromJson(root, clazz);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new DropboxControlException(DropboxControlException.Kind.MALFORMED, "bad field type", e);
        }
        message.validate();
        return message;
    }

    /**
     * Reads at most {@link DropboxControl#MAX_MESSAGE_BYTES} + 1 bytes from {@code in} and decodes them, so an
     * oversized ADU file is rejected without being read into memory. Does not close the stream.
     */
    public static DropboxControlMessage decode(InputStream in) throws IOException, DropboxControlException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
            requireSize(buf.size());
        }
        return decode(buf.toByteArray());
    }

    /**
     * Decodes an ADU that must be an inventory (the server side).
     *
     * @throws DropboxControlException {@code UNEXPECTED_TYPE} for a valid plan, otherwise as {@link #decode(byte[])}
     */
    public static InventoryReport decodeInventory(byte[] bytes) throws DropboxControlException {
        return expect(decode(bytes), InventoryReport.class);
    }

    /**
     * Decodes an ADU that must be a plan (the dropbox side).
     *
     * @throws DropboxControlException {@code UNEXPECTED_TYPE} for a valid inventory, otherwise as
     *                                 {@link #decode(byte[])}
     */
    public static RetentionPlan decodePlan(byte[] bytes) throws DropboxControlException {
        return expect(decode(bytes), RetentionPlan.class);
    }

    private static <T extends DropboxControlMessage> T expect(DropboxControlMessage m, Class<T> clazz) throws
            DropboxControlException {
        if (!clazz.isInstance(m)) {
            throw new DropboxControlException(DropboxControlException.Kind.UNEXPECTED_TYPE,
                                              "expected " + clazz.getSimpleName() + ", got type=" + m.type());
        }
        return clazz.cast(m);
    }

    private static Class<? extends DropboxControlMessage> classFor(String type) throws DropboxControlException {
        if (DropboxControl.TYPE_INVENTORY.equals(type)) return InventoryReport.class;
        if (DropboxControl.TYPE_PLAN.equals(type)) return RetentionPlan.class;
        throw new DropboxControlException(DropboxControlException.Kind.UNKNOWN_TYPE, "type=" + abbreviate(type));
    }

    private static String readType(JsonObject root) throws DropboxControlException {
        JsonElement t = root.get(FIELD_TYPE);
        if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isString()) {
            throw new DropboxControlException(DropboxControlException.Kind.UNKNOWN_TYPE, "missing \"type\"");
        }
        return t.getAsString();
    }

    private static int readVersion(JsonObject root) throws DropboxControlException {
        JsonElement v = root.get(FIELD_VERSION);
        if (v == null || !v.isJsonPrimitive() || !((JsonPrimitive) v).isNumber()) {
            throw new DropboxControlException(DropboxControlException.Kind.UNSUPPORTED_VERSION, "missing \"v\"");
        }
        double d = v.getAsDouble();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new DropboxControlException(DropboxControlException.Kind.UNSUPPORTED_VERSION, "v=" + v);
        }
        return (int) d;
    }

    private static void requireSize(int length) throws DropboxControlException {
        if (length > DropboxControl.MAX_MESSAGE_BYTES) {
            throw new DropboxControlException(DropboxControlException.Kind.TOO_LARGE,
                                              "more than " + DropboxControl.MAX_MESSAGE_BYTES + " bytes");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
