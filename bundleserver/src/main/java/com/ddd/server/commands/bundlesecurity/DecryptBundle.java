package com.ddd.server.commands.bundlesecurity;

import com.ddd.bundlesecurity.SecurityUtils;
import com.ddd.server.bundlesecurity.ServerSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.io.*;
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

            bundlePath = SecurityUtils.unzip(bundlePath);

            if (decryptedPath == null) {
                decryptedPath = receivedProcessingDir + File.separator + "decrypted" + File.separator;
            }

            serverSecurity.decrypt(bundlePath, decryptedPath);

            int startIndex = bundlePath.lastIndexOf("\\") + 1;

            // Extract the file name using substring
            String fileName = bundlePath.substring(startIndex);

            String decryptedBundlePath = decryptedPath + fileName + ".decrypted";

            decryptedBundlePath = SecurityUtils.unzip(decryptedBundlePath);

            System.out.println("Finished decrypting " + decryptedBundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
