package com.ddd.server.commands.keygenerator;

import com.ddd.server.bundlesecurity.ServerSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@Component
@CommandLine.Command(name = "decrypt-bundle", description = "Decrypt bundle")
public class DecryptBundle implements Callable<Void> {
    @CommandLine.Parameters(arity = "1", index = "0")
    String command;

    @CommandLine.Option(names = "-bundle", required = true, description = "Bundle file path")
    private String bundlePath;

    @CommandLine.Option(names = "-key", description = "Key file path")
    private String keyPath;

    @Autowired
    private ServerSecurity serverSecurity;

    @Override
    public Void call() {
        try {
            System.out.println("Decrypting bundle" + bundlePath);

            serverSecurity.decrypt(bundlePath, bundlePath);

            System.out.println("Finished decrypting " + bundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
