package net.discdd.server.commands.messageunicast;

import com.google.gson.Gson;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.utils.StoreADUs;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "message-client",
        description = "Manage client messages by clientId",
        subcommands = {
                ClientMessageCast.ListCmd.class,
                ClientMessageCast.AddCmd.class,
                ClientMessageCast.DeleteAllCmd.class
        }
)
@Component
public class ClientMessageCast implements Callable<Integer> {

    public static final String APP_ID = "net.discdd.bundleclient";

    private final AduStores aduStores;
    static final Gson GSON = new Gson();

    public ClientMessageCast(AduStores aduStores) {
        this.aduStores = aduStores;
    }

    @CommandLine.Parameters(arity = "1", index = "0", hidden = true)
    String ignoredConfigFile;

    StoreADUs sendStore() {
        return aduStores.getSendADUsStorage();
    }

    @Override
    public Integer call() {
        System.out.println("Available subcommands: list, add, delete-all");
        System.out.println("Use '--help' to see all available subcommands and their descriptions.");
        return 0;
    }

    static final class ClientMessagePayload {
        int v = 1;
        long messageId;
        String sentAt;
        String message;
        boolean read = false;
    }

    @CommandLine.Command(name = "list", description = "List queued client messages for a particular client.")
    static class ListCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private ClientMessageCast parent;

        @CommandLine.Parameters(index = "0", description = "Client ID")
        @Nonnull
        private String clientId;

        @CommandLine.Option(
                names = "--display",
                description = "Displays the JSON contents and metadata prepared to be sent."
        )
        private boolean display;

        @Override
        public Integer call() {
            try {
                StoreADUs store = parent.sendStore();
                List<ADU> adus = store.getADUs(clientId, APP_ID).toList();

                if (adus.isEmpty()) {
                    System.out.println("No queued client messages for Client: " + clientId);
                    return 0;
                }

                System.out.println("Queued client messages for Client: " + clientId);

                for (ADU adu : adus) {
                    System.out.printf("aduId=%d bytes=%d file=%s%n",
                                      adu.getADUId(),
                                      adu.getSize(),
                                      adu.getSource().getName()
                    );

                    if (display) {
                        byte[] raw = store.getADU(clientId, APP_ID, adu.getADUId());
                        String json = new String(raw, StandardCharsets.UTF_8);
                        System.out.println("  body=" + json);
                    }
                }
                return 0;

            } catch (Exception e) {
                System.err.println("Failed to list client messages: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "add", description = "Queue a new client message.")
    static class AddCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private ClientMessageCast parent;

        @CommandLine.Parameters(index = "0", description = "Client ID")
        @Nonnull
        private String clientId;

        @CommandLine.Parameters(index = "1", description = "Message text")
        @Nonnull
        private String messageText;

        @CommandLine.Option(
                names = "--message-id",
                description = "Optional stable messageId for testing/dedupe"
        )
        private Long messageId;

        @Override
        public Integer call() {
            try {
                ClientMessagePayload payload = new ClientMessagePayload();
                if (messageId != null) {
                    payload.messageId = messageId;
                } else {
                    long id = UUID.randomUUID().getMostSignificantBits();
                    if (id == Long.MIN_VALUE) {
                        id = 0; // avoid abs overflow edge case
                    }
                    payload.messageId = Math.abs(id);
                }
                payload.sentAt = Instant.now().toString();
                payload.message = messageText;

                byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);

                StoreADUs store = parent.sendStore();

                File created = store.addADU(clientId, APP_ID, bytes, -1);

                if (created == null) {
                    System.err.println("ADU was skipped (likely <= lastAduDeleted).");
                    return 1;
                }

                System.out.printf(
                        "Queued client message for Client: %s: file=%s, messageId=%s, sentAt=%s%n",
                        clientId, created.getName(), payload.messageId, payload.sentAt
                );
                return 0;

            } catch (Exception e) {
                System.err.println("Failed to add client message: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "delete-all", description = "Delete all queued messages (metadata-safe)")
    static class DeleteAllCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private ClientMessageCast parent;

        @CommandLine.Parameters(index = "0", description = "Client ID")
        @Nonnull
        private String clientId;

        @Override
        public Integer call() {
            try {
                StoreADUs store = parent.sendStore();
                List<ADU> adus = store.getADUs(clientId, APP_ID).toList();

                if (adus.isEmpty()) {
                    System.out.println("No queued client messages to delete for Client: " + clientId);
                    return 0;
                }

                long maxId = adus.stream()
                        .mapToLong(ADU::getADUId)
                        .max()
                        .orElseThrow();

                store.deleteAllFilesUpTo(clientId, APP_ID, maxId);
                System.out.printf("Deleted ALL queued client messages for Client: %s (up to %d)%n",
                                  clientId, maxId);
                return 0;

            } catch (Exception e) {
                System.err.println("Failed to delete-all: " + e.getMessage());
                return 1;
            }
        }
    }
}