package net.discdd.server.commands.messageunicast;

import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import net.discdd.server.service.TransportMessageService;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import javax.annotation.Nonnull;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "message-transport", description = "Manage transport messages by transportId")
@Component
public class MessageCast implements Callable<Integer> {

    private final TransportMessageRepository repo;
    private final TransportMessageService service;

    public MessageCast(TransportMessageRepository repo, TransportMessageService service) {
        this.repo = repo;
        this.service = service;
    }

    @CommandLine.Parameters(arity = "1", index = "0", hidden = true)
    String ignoredConfigFile;

    @CommandLine.Command(name = "list", description = "List all transport messages")
    int list() {
        if (repo.count() == 0) {
            System.out.println("There are no messages in the table");
            return 0;
        }

        System.out.println("Transport Messages:");
        repo.findAll().forEach(m -> {
            MessageKey key = m.messageKey;
            System.out.printf("transportId=%s, messageNumber=%d message= %s%n",
                              key.getTransportId(),
                              key.getMessageNumber(),
                              m.message);
        });
        return 0;
    }

    @CommandLine.Command(name = "add", description = "Add a new message to a transportId")
    int add(@CommandLine.Parameters(index = "0", description = "Transport ID") @Nonnull String transportId,

            @CommandLine.Parameters(index = "1", description = "Message text") @Nonnull String messageText) {
        TransportMessage msg = service.createMessage(transportId, messageText);

        System.out.printf("Created message: transportId=%s, messageNumber=%d, message=%s, messageDate=%s%n",
                          msg.messageKey.getTransportId(),
                          msg.messageKey.getMessageNumber(),
                          msg.message,
                          msg.messageDate.toString());

        return 0;
    }

    @CommandLine.Command(name = "delete", description = "Delete a message with transportId and messageNumber")
    int delete(@CommandLine.Parameters(index = "0", description = "Transport ID") String transportId,

               @CommandLine.Parameters(index = "1", description = "Message number") long messageNumber) {
        MessageKey key = new MessageKey(transportId, messageNumber);

        if (!repo.existsById(key)) {
            System.err.println("Message not found: " + key);
            return 1;
        }

        repo.deleteById(key);
        System.out.printf("Deleted message: transportId=%s, messageNumber=%d%n", transportId, messageNumber);
        return 0;
    }

    @CommandLine.Command(name = "delete-all", description = "Delete all transport messages")
    int deleteAll() {
        long count = repo.count();

        if (count == 0) {
            System.out.println("No messages to delete.");
            return 0;
        }

        repo.deleteAll();
        System.out.printf("Deleted %d messages.%n", count);

        return 0;
    }

    @Override
    public Integer call() {
        System.out.println("Available subcommands: list, add, delete, delete-all");
        System.out.println("Use '--help' to see all available subcommands and their descriptions.");
        return 0;
    }
}
