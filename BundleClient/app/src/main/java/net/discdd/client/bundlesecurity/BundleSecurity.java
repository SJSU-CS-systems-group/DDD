package net.discdd.client.bundlesecurity;

import static java.util.logging.Level.INFO;

import android.content.res.Resources;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;

import net.discdd.bundleclient.R;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.EncryptionHeader;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.utils.Constants;
import net.discdd.utils.FileUtils;

import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.client.bundlerouting.ClientBundleGenerator;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());

    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    private static final String LARGEST_BUNDLE_ID_RECEIVED = "Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";
    private static final String BUNDLE_ID_NEXT_COUNTER = "Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";
    private static Path rootFolder;
    private final ClientWindow clientWindow;
    private final ClientBundleGenerator clientBundleGenerator;
    private final boolean isEncryptionEnabled = true;
    private ClientSecurity client = null;
    private int counter = 0;

    public BundleSecurity(Path rootFolder) throws IOException, InvalidKeyException, WindowExceptions.InvalidLength,
            WindowExceptions.BufferOverflow, NoSuchAlgorithmException {
        BundleSecurity.rootFolder = rootFolder;

        Path bundleSecurityPath = rootFolder.resolve(BUNDLE_SECURITY_DIR);

        Path serverKeyPath = bundleSecurityPath.resolve("Server_Keys");

        Path bundleIdNextCounter = rootFolder.resolve(BUNDLE_ID_NEXT_COUNTER);
        FileUtils.createFileWithDefaultIfNeeded(bundleIdNextCounter, "0".getBytes());

        Path largestBundleIdReceived = rootFolder.resolve(LARGEST_BUNDLE_ID_RECEIVED);
        FileUtils.createFileWithDefaultIfNeeded(largestBundleIdReceived, "0".getBytes());

        this.counter = Integer.valueOf(Files.readAllLines(bundleIdNextCounter).get(0));

        /* Initializing Security Module*/

        client = ClientSecurity.initializeInstance(1, bundleSecurityPath, serverKeyPath);
        clientBundleGenerator = ClientBundleGenerator.initializeInstance(client, rootFolder);
        clientWindow = ClientWindow.initializeInstance(5, client.getClientID(), rootFolder);
    }

    public static void initializeKeyPaths(Resources resources, String rootDir) throws IOException {
        var serverKeyPath = Paths.get(rootDir, BundleSecurity.BUNDLE_SECURITY_DIR, "Server_Keys");

        InputStream inServerIdentity = resources.openRawResource(R.raw.server_identity);
        InputStream inServerSignedPre = resources.openRawResource(R.raw.server_signed_pre);
        InputStream inServerRatchet = resources.openRawResource(R.raw.server_ratchet);

        serverKeyPath.toFile().mkdirs();

        Path outServerIdentity = serverKeyPath.resolve("server_identity.pub");
        Path outServerSignedPre = serverKeyPath.resolve("server_signed_pre.pub");
        Path outServerRatchet = serverKeyPath.resolve("server_ratchet.pub");

        Files.copy(inServerIdentity, outServerIdentity, StandardCopyOption.REPLACE_EXISTING);
        inServerIdentity.close();

        Files.copy(inServerSignedPre, outServerSignedPre, StandardCopyOption.REPLACE_EXISTING);
        inServerSignedPre.close();

        Files.copy(inServerRatchet, outServerRatchet, StandardCopyOption.REPLACE_EXISTING);
        inServerRatchet.close();
    }

    public void registerLargestBundleIdReceived(String bundleId) throws WindowExceptions.BufferOverflow, IOException,
            InvalidKeyException, GeneralSecurityException {
        logger.log(INFO, "[BS] Inside registerLargestBundleIdReceived function " + bundleId);
        clientWindow.processBundle(bundleId, client);
        logger.log(INFO, "Receive window is:");
        for (String windowBundleId : clientWindow.getWindow(client)) {
            logger.log(INFO, windowBundleId);
        }
    }

    public ClientBundleGenerator getClientBundleGenerator() {
        return clientBundleGenerator;
    }

    public String generateNewBundleId() throws IOException, InvalidKeyException, GeneralSecurityException {
        return clientBundleGenerator.generateBundleID();
    }

    public UncompressedBundle encryptPayload(Payload payload, Path bundleGenDirPath) throws IOException,
            InvalidKeyException {
        String bundleId = payload.getBundleId();
        logger.log(INFO, "Encrypting payload in bundleId: " + bundleId);
        logger.log(INFO, "[BS] Payload source:" + payload.getSource() + " bundle id " + bundleId);
        Path[] paths;
        if (!this.isEncryptionEnabled) {
            return new UncompressedBundle(bundleId, payload.getSource(), null, null, null);
        }
        paths = client.encrypt(payload.getSource().toPath(), bundleGenDirPath, bundleId);

        EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, paths[0].toFile());
        File source = bundleGenDirPath.resolve(bundleId).toFile();
        EncryptionHeader encHeader =
                EncryptionHeader.builder().clientBaseKey(paths[2].toFile()).clientIdentityKey(paths[3].toFile())
                        .serverIdentityKey(paths[4].toFile()).build();
        return new UncompressedBundle(bundleId, source, encHeader, encryptedPayload, paths[1].toFile());

    }

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) throws NoSessionException,
            InvalidMessageException, DuplicateMessageException, IOException, LegacyMessageException,
            InvalidKeyException {
        File decryptedPayloadJar =
                uncompressedBundle.getSource().toPath().resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar")
                        .toFile();
        logger.log(INFO, "decryptedPayloadJar file path: "+decryptedPayloadJar.getPath());
        logger.log(INFO, "decryptedPayloadJar file exists: "+decryptedPayloadJar.exists());
        String bundleId = "";
        if (this.isEncryptionEnabled) {
            ClientSecurity clientSecurity = ClientSecurity.getInstance();
            clientSecurity.decrypt(uncompressedBundle.getSource().toPath(), uncompressedBundle.getSource().toPath());
            bundleId = clientSecurity.getBundleIDFromFile(uncompressedBundle.getSource().toPath());
            File decryptedPayload = uncompressedBundle.getSource().toPath().resolve(bundleId + ".decrypted").toFile();
            if (decryptedPayload.exists()) {
                decryptedPayload.renameTo(decryptedPayloadJar);
            }
        }
        return new Payload(bundleId, decryptedPayloadJar);
    }

    public ClientWindow getClientWindow() {
        return this.clientWindow;
    }

    public ClientSecurity getClientSecurity() {
        return this.client;
    }
}
