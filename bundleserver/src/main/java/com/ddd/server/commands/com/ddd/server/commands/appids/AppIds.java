package com.ddd.server.commands.com.ddd.server.commands.appids;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "appids",
    description = "List and updatedregistered app ids",
    mixinStandardHelpOptions = true
)
@Component
public class AppIds implements Callable<Integer> {
    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;

    AppIds(RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
    }
    @CommandLine.Command(
        name = "list",
        description = "List all registered app ids"
    )
    int list() {
        System.out.println("Registered app ids:");
        registeredAppAdapterRepository.findAll().forEach(r -> System.out.println(r.getAppId() + " registered to " + r.getAddress()));
        return 0;
    }
    @CommandLine.Command(
        name = "update",
        description = "Add or update an appId to server address mapping"
    )
    int update(
        @CommandLine.Parameters(
            index = "0",
            description = "The appId to add or update"
        ) String appId,
        @CommandLine.Parameters(
            index = "1",
            description = "The server address to map to the appId"
        ) String address
    ) {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter(appId, address));
        System.out.printf("%s registered to %s%n", appId, address);
        return 0;
    }
    @CommandLine.Command(
        name = "delete",
        description = "Delete an appId"
    )
    int delete(
        @CommandLine.Parameters(
            index = "0",
            description = "The appId to delete"
        ) String appId
    ) {
        registeredAppAdapterRepository.deleteById(appId);
        System.out.printf("%s deleted%n", appId);
        return 0;
    }

    @CommandLine.Parameters(arity = "1", index = "0", hidden = true)
    String command;

    @Override
    public Integer call() throws Exception {
        System.out.println("Use --help to see available commands");
        return 1;
    }
}
