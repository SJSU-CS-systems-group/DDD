package net.discdd.server.commands.messageunicast;

import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import net.discdd.server.service.TransportMessageService;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "message-transport",
        description = "Manage transport messages by transportId",
        mixinStandardHelpOptions = true,
        subcommands = { CommandLine.HelpCommand.class }
)
@Component
public class MessageCast implements Callable<Integer> {

    private final TransportMessageRepository repo;
    private final TransportMessageService service;

    public MessageCast(TransportMessageRepository repo, TransportMessageService service) {
        this.repo = repo;
        this.service = service;
    }
    @CommandLine.Command(
            name = "list",
            description = "List all transport messages"
    )
    int list() {
        System.out.println("Transport Messages:");
        repo.findAll().forEach(m -> {
            MessageKey key = m.messageKey;
            System.out.printf(
                    "transportId=%d, messageNumber=%d | %s%n",
                    key.getTransportId(),
                    key.getMessageNumber(),
                    m.message
            );
        });
        return 0;
    }

    @CommandLine.Command(
            name = "add",
            description = "Add a new message to a transportId"
    )
    int add(
            @CommandLine.Parameters(index = "0", description = "Transport ID")
            int transportId,

            @CommandLine.Parameters(index = "1", description = "Message text")
            String messageText
    ) {
        TransportMessage msg = service.createMessage(transportId, messageText);

        System.out.printf(
                "Created message: transportId=%d, messageNumber=%d%n",
                msg.messageKey.getTransportId(),
                msg.messageKey.getMessageNumber()
        );

        return 0;
    }

    @CommandLine.Command(
            name = "delete",
            description = "Delete a message with transportId and messageNumber"
    )
    int delete(
            @CommandLine.Parameters(index = "0", description = "Transport ID")
            long transportId,

            @CommandLine.Parameters(index = "1", description = "Message number")
            long messageNumber
    ) {
        MessageKey key = new MessageKey(transportId, messageNumber);

        if (!repo.existsById(key)) {
            System.err.println("Message not found: " + key);
            return 1;
        }

        repo.deleteById(key);
        System.out.printf(
                "Deleted message: transportId=%d, messageNumber=%d%n",
                transportId,
                messageNumber
        );
        return 0;
    }

    @Override
    public Integer call() {
        System.out.println("Use subcommands: list, add, delete");
        return 0;
    }
}
