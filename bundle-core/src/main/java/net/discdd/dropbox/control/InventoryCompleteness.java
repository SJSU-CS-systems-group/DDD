package net.discdd.dropbox.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * Optional inventory metadata understood by new dropboxes and servers without changing the v1 message class.
 * Only an explicit JSON boolean {@code inventoryComplete:true} lets the server forget sightings omitted from
 * this report. It never authorizes deletion of payloads; older reports have no such field and may have been
 * silently truncated.
 */
public final class InventoryCompleteness {
    private static final String FIELD = "inventoryComplete";

    private InventoryCompleteness() {}

    public static byte[] encode(InventoryReport report, boolean complete) throws DropboxControlException {
        JsonObject root = JsonParser.parseString(new String(DropboxControlCodec.encode(report),
                                                            StandardCharsets.UTF_8)).getAsJsonObject();
        root.addProperty(FIELD, complete);
        byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > DropboxControl.MAX_MESSAGE_BYTES) {
            throw new DropboxControlException(DropboxControlException.Kind.TOO_LARGE,
                                              "inventory with completeness metadata exceeds message bound");
        }
        return encoded;
    }

    /** Missing, malformed, or non-boolean metadata is conservatively incomplete. */
    public static boolean isComplete(byte[] encoded) {
        try {
            JsonElement value = JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8))
                    .getAsJsonObject().get(FIELD);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() &&
                    value.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
