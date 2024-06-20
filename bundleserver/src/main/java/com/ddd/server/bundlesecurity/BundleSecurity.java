package com.ddd.server.bundlesecurity;

import com.ddd.model.*;
import com.ddd.utils.Constants;
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
import java.util.logging.Logger;

import static java.util.logging.Level.*;

@Service
public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());
    @Value("${bundle-server.application-data-manager.state-manager.bundle-id-next-counter}")
    private String BUNDLE_ID_NEXT_COUNTER;
    @Autowired
    private ServerSecurity serverSecurity;

    private final boolean encryptionEnabled = true;

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

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
        String bundleId = "";
        File decryptedPayloadJar = new File(uncompressedBundle.getSource().getAbsolutePath() + File.separator +
                                                    Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar");

        if (this.encryptionEnabled) {
            try {
                this.serverSecurity.decrypt(uncompressedBundle.getSource().toPath(),
                                            uncompressedBundle.getSource().toPath());
            } catch (Exception e) {
                // TODO
                logger.log(SEVERE, "[BundleSecurity] Failed to decrypt payload");
                // e.printStackTrace();
                return null;
            }

            try {
                bundleId = this.serverSecurity.getBundleIDFromFile(uncompressedBundle.getSource().toPath());
            } catch (Exception e) {
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
        var paths = this.serverSecurity.encrypt(payload.getSource().toPath(), bundleGenDirPath, bundleId, clientId);

        EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, paths[0].toFile());

        File source = new File(bundleGenDirPath + File.separator + bundleId);
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

    public String getServerId() throws NoSuchAlgorithmException {
        return serverSecurity.getServerId();
    }

    public boolean bundleServerIdMatchesCurrentServer(String receivedServerId) throws NoSuchAlgorithmException {
        return receivedServerId.equals(getServerId());
    }

}
