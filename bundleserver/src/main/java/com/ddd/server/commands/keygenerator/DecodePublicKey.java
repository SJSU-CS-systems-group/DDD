package com.ddd.server.commands.keygenerator;

import java.io.File;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import com.ddd.server.bundlesecurity.SecurityUtils;
import com.ddd.server.bundlesecurity.SecurityExceptions.EncodingException;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static java.util.logging.Level.*;

@Component
@CommandLine.Command(name = "decode-pub-key", description = "Decode public key given private key")
public class DecodePublicKey implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(DecodePublicKey.class.getName());
    @Value("${bundle-server.bundle-security.server-serverkeys-path}")
    private String storePath;

    @Parameters(arity = "1", index = "0")
    String command;

    @Option(names = "-pvtin-file", description = "Private key file name")
    private String pvtFilename;

    @Option(names = "-pvtin-base64", description = "Private key base64")
    private String pvtbase64;

    @Option(names = "-pvtin-raw", description = "Private key raw")
    private String pvtraw;

    @Option(names = "-pubout", description = "Public key file name")
    private String pubFilename;

    private ECPublicKey extractPublicKey(byte[] privateKey) throws InvalidKeyException {
        IdentityKeyPair identityKeyPair = new IdentityKeyPair(privateKey);
        return identityKeyPair.getPublicKey().getPublicKey();
    }

    private byte[] decodeFile() throws EncodingException {
        return SecurityUtils.decodePrivateKeyFromFile(storePath + File.separator + pvtFilename);
    }

    private byte[] decodeBase64() {
        return Base64.getUrlDecoder().decode(pvtbase64);
    }

    private byte[] decodeRaw() {
        return pvtraw.getBytes();
    }

    private void writeToFile(ECPublicKey pubKey) throws EncodingException {
        SecurityUtils.createEncodedPublicKeyFile(pubKey, storePath + File.separator + pubFilename);
        logger.log(INFO, "Written to file");
    }

    private void print(ECPublicKey pubKey) {
        logger.log(WARNING, "Extracted public key: " + Base64.getUrlEncoder().encodeToString(pubKey.serialize()));
    }

    @Override
    public Void call() {
        byte[] serializedPrivateKey = null;
        if (pvtFilename != null) {
            try {
                serializedPrivateKey = decodeFile();
            } catch (EncodingException e) {
                logger.log(SEVERE, "Couldn't decode file, check file name.");
            }
        } else if (pvtbase64 != null) {
            serializedPrivateKey = decodeBase64();
        } else if (pvtraw != null) {
            serializedPrivateKey = decodeRaw();
        }

        if (serializedPrivateKey != null) {
            ECPublicKey pubKey;
            try {
                pubKey = extractPublicKey(serializedPrivateKey);
                if (pubFilename != null) {
                    writeToFile(pubKey);
                } else {
                    print(pubKey);
                }
            } catch (Exception e) {
                logger.log(SEVERE, e.getMessage());
                logger.log(SEVERE, "Couldn't complete extraction.");
            }
        }

        return null;
    }
}
