package com.ddd.server.commands.keygenerator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static java.util.logging.Level.*;

@Component
@CommandLine.Command(name = "generate-keys", description = "Generate pair of EC keys")
public class GenerateKeys implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(GenerateKeys.class.getName());
    private final String PUB_KEY_HEADER = "-----BEGIN EC PUBLIC KEY-----";
    private final String PUB_KEY_FOOTER = "-----END EC PUBLIC KEY-----";
    private final String PVT_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    private final String PVT_KEY_FOOTER = "-----END EC PRIVATE KEY-----";

    @Value("${bundle-server.bundle-security.server-serverkeys-path}")
    private String storePath;

    private String base64PrivateKey;
    private String base64PublicKey;

    @Parameters(arity = "1", index = "0")
    String command;

    @Option(names = "-type", defaultValue = "identity", description = "Type of key pair: identity, ratchet, signedpre")
    private String type;

    @Option(names = "-pvtout", description = "Private key file name")
    private String privateKeyOutputFileName;

    @Option(names = "-pubout", description = "Public key file name")
    private String publicKeyOutputFileName;

    private void createKeyPair() {
        logger.log(INFO, "Generating " + type + " keys...");
        ECKeyPair keyPair = Curve.generateKeyPair();

        if (type.toLowerCase().equals("identity")) {
            IdentityKeyPair identityKeyPair =
                    new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());

            base64PrivateKey = Base64.getUrlEncoder().encodeToString(identityKeyPair.serialize());
            base64PublicKey =
                    Base64.getUrlEncoder().encodeToString(identityKeyPair.getPublicKey().getPublicKey().serialize());
        } else {
            base64PrivateKey = Base64.getUrlEncoder().encodeToString(keyPair.getPrivateKey().serialize());
            base64PublicKey = Base64.getUrlEncoder().encodeToString(keyPair.getPublicKey().serialize());
        }
    }

    private void writeToFile(boolean isPrivate, File file) {
        String encodedKey = (isPrivate ? PVT_KEY_HEADER : PUB_KEY_HEADER) + "\n";
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            encodedKey += isPrivate ? base64PrivateKey : base64PublicKey;
            encodedKey += "\n" + (isPrivate ? PVT_KEY_FOOTER : PUB_KEY_FOOTER);
            stream.write(encodedKey.getBytes());
            logger.log(INFO, "Written to file");
        } catch (Exception e) {
            logger.log(SEVERE,
                    "com.ddd.server.keygenerator.commands.GenerateKeys.writeToFile error: " + e.getMessage());
        }
    }

    private void verifyWrite(boolean isPrivate, String filename) {
        File file = new File(storePath + "/" + filename);

        if (file.exists()) {
            String s = System.console().readLine("Do you want to overwrite " + file.getPath() + "? [y/n]: ");

            if ("y".equalsIgnoreCase(s)) {
                logger.log(WARNING, "Overwriting " + file.getPath() + "...");
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
                logger.log(SEVERE,
                        "com.ddd.server.keygenerator.commands.GenerateKeys.verifyWrite error: " + e.getMessage());
            }
            logger.log(WARNING, "Writing to " + file.getPath() + "...");
            writeToFile(isPrivate, file);
        }
    }

    private void printPrivateKey() {
        logger.log(INFO, "Private key: " + base64PrivateKey);
    }

    private void printPublicKey() {
        logger.log(INFO,
                "Public key: " + base64PublicKey);
    }

    private void validInputs() throws IllegalArgumentException {
        if (!type.toLowerCase().equals("identity") && !type.toLowerCase().equals("ratchet") &&
                !type.toLowerCase().equals("signedpre")) {
            throw new IllegalArgumentException("Type must be one of the following: identity, ratchet, signedpre");
        }
    }

    @Override
    public Void call() {
        try {
            validInputs();
            createKeyPair();
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
        } catch (Exception e) {
            logger.log(SEVERE, e.getMessage());
        }

        return null;
    }

}