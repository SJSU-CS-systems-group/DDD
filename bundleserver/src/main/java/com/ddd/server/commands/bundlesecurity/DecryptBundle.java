package com.ddd.server.commands.bundlesecurity;

import com.ddd.server.bundlesecurity.SecurityUtils;
import com.ddd.server.bundlesecurity.ServerSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@Component
@CommandLine.Command(name = "decrypt-bundle", description = "Decrypt bundle")
public class DecryptBundle implements Callable<Void> {
    @Value("${bundle-server.bundle-transmission.received-processing-directory}")
    private String receivedProcessingDir;

    @CommandLine.Parameters(arity = "1", index = "0")
    String command;

    @CommandLine.Option(names = "--bundle", required = true, description = "Bundle file path")
    private String bundlePath;

    @CommandLine.Option(names = "--decrypted-path", description = "Decrypted bundle file path")
    private String decryptedPath;

    @Autowired
    private ServerSecurity serverSecurity;

    @Override
    public Void call() {
        try {
            System.out.println("Decrypting bundle" + bundlePath);

            if (decryptedPath == null) {
                decryptedPath = receivedProcessingDir + File.separator + "decrypted" + File.separator;
            }

            serverSecurity.decrypt(bundlePath, decryptedPath);

            System.out.println("Finished decrypting " + bundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
