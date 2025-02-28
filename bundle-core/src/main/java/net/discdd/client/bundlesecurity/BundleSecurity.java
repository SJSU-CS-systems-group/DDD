package net.discdd.client.bundlesecurity;

import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.client.bundlerouting.ClientBundleGenerator;
import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.tls.GrpcSecurity;
import net.discdd.utils.Constants;
import org.bouncycastle.operator.OperatorCreationException;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());
    private ClientPaths clientPaths;
    private final ClientWindow clientWindow;
    private final ClientBundleGenerator clientBundleGenerator;
    private final boolean isEncryptionEnabled = true;
    private ClientSecurity client = null;
    private GrpcSecurity clientGrpcSecurity = null;
    private int counter = 0;

    public BundleSecurity(ClientPaths clientPaths) throws IOException, InvalidKeyException,
            WindowExceptions.BufferOverflow, NoSuchAlgorithmException {
        this.clientPaths = clientPaths;

        this.counter = Integer.valueOf(Files.readAllLines(clientPaths.bundleIdNextCounter).get(0));

        /* Initializing Security Module*/
        client = ClientSecurity.initializeInstance(1, clientPaths);
        clientBundleGenerator = ClientBundleGenerator.initializeInstance(client, clientPaths);
        clientWindow = ClientWindow.initializeInstance(5, client.getClientID(), clientPaths);
        try {
            this.clientGrpcSecurity = GrpcSecurity.initializeInstance(clientPaths.grpcSecurityPath,
                    SecurityUtils.CLIENT);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeyException | InvalidAlgorithmParameterException |
                 CertificateException | NoSuchProviderException | OperatorCreationException e) {
            logger.log(SEVERE, "Failed to initialize GrpcSecurity for CLIENT", e);
        }

    }

    // TODO: this function makes me sad! it should not be static. We should probably inject BundleSecurity
    // into Bundle transport so that everything can be set up properly
    public static void initializeKeyPaths(ClientPaths clientPaths, InputStream inServerIdentity,
                                          InputStream inServerSignedPre, InputStream inServerRatchet) throws IOException {
        Files.copy(inServerIdentity, clientPaths.outServerIdentity, StandardCopyOption.REPLACE_EXISTING);
        inServerIdentity.close();

        Files.copy(inServerSignedPre, clientPaths.outServerSignedPre, StandardCopyOption.REPLACE_EXISTING);
        inServerSignedPre.close();

        Files.copy(inServerRatchet, clientPaths.outServerRatchet, StandardCopyOption.REPLACE_EXISTING);
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

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) throws NoSessionException,
            InvalidMessageException, DuplicateMessageException, IOException, LegacyMessageException,
            InvalidKeyException {
        File decryptedPayloadJar =
                uncompressedBundle.getSource().toPath().resolve(Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar")
                        .toFile();
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
    public GrpcSecurity getClientGrpcSecurity() {
        return this.clientGrpcSecurity;
    }
}
