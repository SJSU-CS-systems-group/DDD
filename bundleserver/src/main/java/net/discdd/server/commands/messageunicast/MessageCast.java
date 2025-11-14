package net.discdd.server.commands.messageunicast;

import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.repository.entity.TransportMessage;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "message-transport",
        description = "Manage transport messages by their transportId",
        mixinStandardHelpOptions = true
)
@Component
public class MessageCast implements Callable<Integer> {

    private final TransportMessageRepository transportMessageRepository;

    public MessageCast(TransportMessageRepository transportMessageRepository) {
        this.transportMessageRepository = transportMessageRepository;
    }

    @CommandLine.Command(name = "list", description = "List all transport messages")
    int list() {
        System.out.println("Transport Messages:");
        transportMessageRepository.findAll()
                .forEach(m -> System.out.println(m.messageId + " -> " + m.message));
        return 0;
    }

    @CommandLine.Command(name = "update", description = "Add or update a transport message by transportId")
    int update(
            @CommandLine.Parameters(index = "0", description = "The transportId to add or update")
            String transportId,
            @CommandLine.Parameters(index = "1", description = "The message to associate with the transportId")
            String message
    ) {
        transportMessageRepository.save(new TransportMessage(transportId, message));
        System.out.printf("Transport message updated: %s -> %s%n", transportId, message);
        return 0;
    }

    @CommandLine.Command(name = "delete", description = "Delete a transport message by transportId")
    int delete(
            @CommandLine.Parameters(index = "0", description = "The transportId to delete")
            String transportId
    ) {

        if (!transportMessageRepository.existsById(transportId)) {
            System.err.println("Transport message not found: " + transportId);
            return 1;
        }

        transportMessageRepository.deleteById(transportId);
        System.out.printf("Transport message deleted: %s%n", transportId);
        return 0;
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("Use --help to see available subcommands (list, update, delete)");
        return 1;
    }
}
