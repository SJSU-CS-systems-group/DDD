package net.discdd.dropbox.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryCompletenessTest {
    @Test
    void optionalFieldIsReadableByOldCodecAndMissingOrNonBooleanMeansPartial() throws Exception {
        InventoryReport report = DropboxControlCodecTest.sampleInventory();
        byte[] full = InventoryCompleteness.encode(report, true);
        assertEquals(report, DropboxControlCodec.decodeInventory(full));
        assertTrue(InventoryCompleteness.isComplete(full));
        assertFalse(InventoryCompleteness.isComplete(InventoryCompleteness.encode(report, false)));
        assertFalse(InventoryCompleteness.isComplete(DropboxControlCodec.encode(report)));

        JsonObject json = JsonParser.parseString(new String(full, StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("inventoryComplete", "true");
        assertFalse(InventoryCompleteness.isComplete(json.toString().getBytes(StandardCharsets.UTF_8)));
        assertFalse(InventoryCompleteness.isComplete("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void metadataDoesNotBypassTheFourMiBWireBound() throws Exception {
        byte[] small = InventoryCompleteness.encode(DropboxControlCodecTest.sampleInventory(), true);
        assertTrue(small.length <= DropboxControl.MAX_MESSAGE_BYTES);

        List<InventoryReport.UpstreamEntry> up = new ArrayList<>();
        List<InventoryReport.DownstreamEntry> down = new ArrayList<>();
        String padding = "x".repeat(90);
        for (int i = 0; i < DropboxControl.MAX_ENTRIES_PER_LIST; i++) {
            String id = String.format("%05d%s", i, padding);
            up.add(new InventoryReport.UpstreamEntry(id, 1, null));
            down.add(new InventoryReport.DownstreamEntry(id, 1, null, 0, null));
        }
        InventoryReport oversized = new InventoryReport(1, 0, 1, 1, null, up, down, List.of());
        DropboxControlException error = assertThrows(DropboxControlException.class,
                () -> InventoryCompleteness.encode(oversized, false));
        assertEquals(DropboxControlException.Kind.TOO_LARGE, error.getKind());
    }
}
