package net.discdd.server.bundlesecurity;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.RecencyBlob;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.utils.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@Service
public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());
    @Value("${bundle-server.application-data-manager.state-manager.bundle-id-next-counter}")
    private String BUNDLE_ID_NEXT_COUNTER;

    @Autowired
    private ServerSecurity serverSecurity;

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

    public String[] encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) {
        Path bundleDir = Path.of(encPath, bundleID);

        File bundleIdFile = bundleDir.resolve(Constants.BUNDLE_IDENTIFIER_FILE_NAME).toFile();
        try {
            Files.write(bundleIdFile.toPath(), bundleID.getBytes());
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        try {
            Files.copy(new File(toBeEncPath).toPath(), bundleDir.resolve(bundleID + ".jar"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.log(INFO, "mock encrypt implementation");
        return null;
    }

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
        logger.log(INFO, "[BundleSecurity] Decrypting payload");
        String bundleId = "";
        File decryptedPayloadJar = uncompressedBundle.getSource()
                .toPath()
                .resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar")
                .toFile();

        try {
            this.serverSecurity.decrypt(uncompressedBundle.getSource().toPath(),
                                        uncompressedBundle.getSource().toPath());
            logger.log(FINE, "[BundleSecurity] decrypted payload");
        } catch (Exception e) {
            // TODO
            logger.log(SEVERE,
                       "[BundleSecurity] Failed to decrypt payload" + uncompressedBundle.getSource().toPath(),
                       e);
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
        return new Payload(bundleId, decryptedPayloadJar);
    }

    public boolean bundleServerIdMatchesCurrentServer(String receivedServerId) throws NoSuchAlgorithmException {
        return receivedServerId.equals(serverSecurity.getServerId());
    }

    public byte[] signRecencyBlob(RecencyBlob blob) throws InvalidKeyException {
        return SecurityUtils.signMessageRaw(blob.toByteArray(), serverSecurity.getSigningKey());
    }

    public byte[] getIdentityPublicKey() {
        return serverSecurity.getIdentityPublicKey().serialize();
    }

    public long getCounterFromBundlePath(Path bundlePath, boolean direction) throws GeneralSecurityException,
            IOException, InvalidKeyException {
        return serverSecurity.getCounterFromBundlePath(bundlePath, direction);
    }
}
