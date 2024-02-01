package com.ddd.server.keygenerator.commands;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.Callable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@CommandLine.Command(name = "generate-keys", description = "Generate pair of EC keys")
public class GenerateKeys implements Callable<Integer> {
    private final String PUB_KEY_HEADER = "-----BEGIN EC PUBLIC KEY-----";
    private final String PUB_KEY_FOOTER = "-----END EC PUBLIC KEY-----";
    private final String PVT_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    private final String PVT_KEY_FOOTER = "-----END EC PRIVATE KEY-----";

    @Value("${bundle-server.bundle-security.server-serverkeys-path}")
    private String storePath;

    private IdentityKeyPair keyPair;
    private String base64PrivateKey;
    private String base64PublicKey;

    @Parameters(arity = "1", index = "0")
    String command;

    @Option(names = "-pvtout", description = "Private key file name")
    private String privateKeyOutputFileName;

    @Option(names = "-pubout", description = "Private key file name")
    private String publicKeyOutputFileName;

    private void createIdentityKeyPair() {
        System.out.println("Generating keys...");
        ECKeyPair identityKeyPair = Curve.generateKeyPair();
        keyPair = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                identityKeyPair.getPrivateKey());
        base64PrivateKey = Base64.getUrlEncoder().encodeToString(keyPair.serialize());
        base64PublicKey = Base64.getUrlEncoder().encodeToString(keyPair.getPublicKey().getPublicKey().serialize());
    }

    private void writeToFile(boolean isPrivate, File file) {
        String encodedKey = (isPrivate ? PVT_KEY_HEADER : PUB_KEY_HEADER) + "\n";
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            encodedKey += isPrivate ? base64PrivateKey : base64PublicKey;
            encodedKey += "\n" + (isPrivate ? PVT_KEY_FOOTER : PUB_KEY_FOOTER);
            stream.write(encodedKey.getBytes());
            System.out.println("Written to file");
        }  catch (Exception e) {
            System.out.println("com.ddd.server.keygenerator.commands.GenerateKeys.writeToFile error: " + e.getMessage());
        }
    }

    private void verifyWrite(boolean isPrivate, String filename) {
        File file = new File(storePath + "/" + filename);

        if (file.exists()) {
            String s = System.console().readLine("Do you want to overwrite " + file.getPath() + "? [y/n]: ");

            if ("y".equalsIgnoreCase(s)) {
                System.out.println("Overwriting " + file.getPath() + "...");
                writeToFile(isPrivate, file);
            } else {
                if (isPrivate) {
                    printPrivateKey();
                } else {
                    printPublicKey();
                }
            }
        } else {
            try {
                file.createNewFile();
            } catch (IOException e) {
                System.out.println("com.ddd.server.keygenerator.commands.GenerateKeys.verifyWrite error: " + e.getMessage());
            }
            System.out.println("Writing to " + file.getPath() + "...");
            writeToFile(isPrivate, file);
        }
    }
    
    private void printPrivateKey() {
        System.out.println("Private key: " + base64PrivateKey);
    }

    private void printPublicKey() {
        System.out.println("Public key: " + base64PublicKey);
    }

    @Override
    public Integer call() {
        createIdentityKeyPair();
        if (privateKeyOutputFileName == null) {
            printPrivateKey();
        } else {
            verifyWrite(true, privateKeyOutputFileName);
        }
        
        if (publicKeyOutputFileName == null) {
            printPublicKey();
        } else {
            verifyWrite(false, publicKeyOutputFileName);
        }

        return 0;
    }

}