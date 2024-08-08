package net.discdd.server.bundlesecurity;

import net.discdd.bundlesecurity.InvalidClientSessionException;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.EncryptionHeader;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.utils.Constants;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Service
public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());
    @Value("${bundle-server.application-data-manager.state-manager.bundle-id-next-counter}")
    private String BUNDLE_ID_NEXT_COUNTER;
    @Autowired
    private ServerSecurity serverSecurity;

    private final boolean encryptionEnabled = true;

    private Long getRecvdBundleIdCounter(String bundleId) {
        return Long.valueOf(bundleId.split("-")[1]);
    }

    @PostConstruct
    private void init() {
        File bundleIdNextCounter = new File(BUNDLE_ID_NEXT_COUNTER);

        try {
            bundleIdNextCounter.getParentFile().mkdirs();
            bundleIdNextCounter.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getClientIdFromBundleId(String bundleId) {
        String clientId = "";
        if (bundleId.contains("-")) {
            clientId = bundleId.split("-")[0];
        } else {
            clientId = bundleId.split("#")[0];
        }
        logger.log(INFO, "[BundleSecurity] Client id corresponding to bundle id: " + bundleId + " is " + clientId);
        return clientId;
    }

    public String[] encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) {
        Path bundleDir = Path.of(encPath, bundleID);

        File bundleIdFile = bundleDir.resolve(Constants.BUNDLE_IDENTIFIER_FILE_NAME).toFile();
        try {
            FileUtils.writeLines(bundleIdFile, Arrays.asList(bundleID));
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        try {
            FileUtils.copyFile(new File(toBeEncPath), bundleDir.resolve(bundleID + ".jar").toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.log(INFO, "mock encrypt implementation");
        return null;
    }

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
        logger.log(INFO, "[BundleSecurity] Decrypting payload");
        String bundleId = "";
        File decryptedPayloadJar =
                uncompressedBundle.getSource().toPath().resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar")
                        .toFile();

        if (this.encryptionEnabled) {
            try {
                this.serverSecurity.decrypt(uncompressedBundle.getSource().toPath(),
                                            uncompressedBundle.getSource().toPath());
                logger.log(FINE, "[BundleSecurity] decrypted payload");
            } catch (Exception e) {
                // TODO
                logger.log(SEVERE, "[BundleSecurity] Failed to decrypt payload");
                // e.printStackTrace();
                return null;
            }

            try {
                bundleId = this.serverSecurity.getBundleIDFromFile(uncompressedBundle.getSource().toPath());
                logger.log(FINE, "[BundleSecurity] Got bundleId from File");
            } catch (Exception e) {
                logger.log(WARNING, "[BundleSecurity] Unable to get bundleId from File");
                e.printStackTrace();
            }
            File decryptedPayload = uncompressedBundle.getSource().toPath().resolve(bundleId + ".decrypted").toFile();
            if (decryptedPayload.exists()) {
                decryptedPayload.renameTo(decryptedPayloadJar);
            }
        }
        return new Payload(bundleId, decryptedPayloadJar);
    }

    public UncompressedBundle encryptPayload(String clientId, Payload payload, Path bundleGenDirPath) throws InvalidClientSessionException, IOException, NoSuchAlgorithmException, InvalidKeyException {
        String bundleId = payload.getBundleId();
        if (!this.encryptionEnabled) {
            return new UncompressedBundle(bundleId, payload.getSource(), null, null, null);
        }
        Path[] paths = new Path[0];
        try {
            paths = this.serverSecurity.encrypt(payload.getSource().toPath(), bundleGenDirPath, bundleId, clientId);
        } catch (InvalidClientSessionException e) {
            throw new RuntimeException(e);
        }

        EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, paths[0].toFile());

        File source = bundleGenDirPath.resolve(bundleId).toFile();
        EncryptionHeader encHeader =
                EncryptionHeader.builder().serverSignedPreKey(paths[2].toFile()).serverIdentityKey(paths[3].toFile())
                        .serverRatchetKey(paths[4].toFile()).build();
        return new UncompressedBundle( // TODO get encryption header, payload signature
                                       bundleId, source, encHeader, encryptedPayload, paths[1].toFile());
    }

    public int isNewerBundle(Path bundlePath, String lastReceivedBundleID) throws GeneralSecurityException,
            IOException, InvalidKeyException {
        return this.serverSecurity.isNewerBundle(bundlePath, lastReceivedBundleID);
    }

    public boolean bundleServerIdMatchesCurrentServer(String receivedServerId) throws NoSuchAlgorithmException {
        return receivedServerId.equals(serverSecurity.getServerId());
    }
}
