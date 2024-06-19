package com.ddd.client.bundlesecurity;

import android.content.res.Resources;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import com.ddd.bundleclient.HelloworldActivity;
import com.ddd.bundleclient.R;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions;
import com.ddd.client.bundlerouting.ClientBundleGenerator;
import com.ddd.client.bundlerouting.ClientWindow;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.EncryptionHeader;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.Constants;

import org.apache.commons.lang3.StringUtils;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

public class BundleSecurity {
    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());

    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    private static final String LARGEST_BUNDLE_ID_RECEIVED = "/Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";
    private static final String BUNDLE_ID_NEXT_COUNTER = "/Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";
    private static Path rootFolder;
    private final ClientWindow clientWindow;
    private final ClientBundleGenerator clientBundleGenerator;
    private final String clientId = "client0";
    private final boolean isEncryptionEnabled = true;
    private ClientSecurity client = null;
    private int counter = 0;

    public BundleSecurity(Path rootFolder) throws IOException, InvalidKeyException, WindowExceptions.InvalidLength,
            WindowExceptions.BufferOverflow, NoSuchAlgorithmException {
        BundleSecurity.rootFolder = rootFolder;

        Path bundleSecurityPath = rootFolder.resolve(BUNDLE_SECURITY_DIR);

        Path serverKeyPath = bundleSecurityPath.resolve("Server_Keys");

        Path bundleIdNextCounter = rootFolder.resolve(BUNDLE_ID_NEXT_COUNTER);

        bundleIdNextCounter.toFile().getParentFile().mkdirs();
        bundleIdNextCounter.toFile().createNewFile();
        Path largestBundleIdReceived = rootFolder.resolve(LARGEST_BUNDLE_ID_RECEIVED);

        largestBundleIdReceived.toFile().getParentFile().mkdirs();
        largestBundleIdReceived.toFile().createNewFile();

        this.counter = Integer.valueOf(Files.readAllLines(bundleIdNextCounter).get(0));

        /* Initializing Security Module*/

        client = ClientSecurity.initializeInstance(1, bundleSecurityPath, serverKeyPath);
        clientBundleGenerator = ClientBundleGenerator.initializeInstance(client, rootFolder);
        clientWindow = ClientWindow.initializeInstance(5, client.getClientID(), rootFolder);
        Log.d(HelloworldActivity.TAG, "Kuch Bhi");

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

        Files.copy(inServerIdentity, outServerIdentity);
        inServerIdentity.close();

        Files.copy(inServerSignedPre, outServerSignedPre);
        inServerSignedPre.close();

        Files.copy(inServerRatchet, outServerRatchet);
        inServerRatchet.close();
    }

    private String getLargestBundleIdReceived() {
        String bundleId = "";
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(new File(rootFolder + LARGEST_BUNDLE_ID_RECEIVED)))) {
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                bundleId = line.trim();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.log(INFO, "[BS] Largest bundle id received so far: " + bundleId);
        return bundleId.trim();
    }

    private Long getRecvdBundleIdCounter(String bundleId) {
        return Long.valueOf(bundleId.split("#")[1]);
    }

    private int compareReceivedBundleIds(String a, String b) {
        return this.getRecvdBundleIdCounter(a).compareTo(this.getRecvdBundleIdCounter(b));
    }

    private void writeCounterToDB() {
        try (BufferedWriter bufferedWriter = new BufferedWriter(
                new FileWriter(new File(rootFolder + BUNDLE_ID_NEXT_COUNTER)))) {
            bufferedWriter.write(String.valueOf(this.counter));
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public void decryptBundleContents(UncompressedPayload bundle) {
        logger.log(INFO, "[BS] Decrypting contents of the bundle with id: " + bundle.getBundleId());
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
        File source = new File(bundleGenDirPath + File.separator + bundleId);
        EncryptionHeader encHeader =
                EncryptionHeader.builder().clientBaseKey(paths[2].toFile()).clientIdentityKey(paths[3].toFile())
                        .serverIdentityKey(paths[4].toFile()).build();
        return new UncompressedBundle(bundleId, source, encHeader, encryptedPayload, paths[1].toFile());

    }

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) throws NoSessionException,
            InvalidMessageException, DuplicateMessageException, IOException, LegacyMessageException,
            InvalidKeyException {
        File decryptedPayloadJar = new File(uncompressedBundle.getSource().getAbsolutePath() + File.separator +
                                                    Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar");
        String bundleId = "";
        if (this.isEncryptionEnabled) {
            ClientSecurity clientSecurity = ClientSecurity.getInstance();
            clientSecurity.decrypt(uncompressedBundle.getSource().toPath(), uncompressedBundle.getSource().toPath());
            bundleId = clientSecurity.getBundleIDFromFile(uncompressedBundle.getSource().toPath());
            File decryptedPayload = new File(
                    uncompressedBundle.getSource().getAbsolutePath() + File.separator + bundleId + ".decrypted");
            if (decryptedPayload.exists()) {
                decryptedPayload.renameTo(decryptedPayloadJar);
            }
        }
        return new Payload(bundleId, decryptedPayloadJar);
    }

    public boolean isLatestReceivedBundleId(String bundleId) {
        String largestBundleIdReceived = this.getLargestBundleIdReceived();
        return (StringUtils.isEmpty(largestBundleIdReceived) ||
                this.compareReceivedBundleIds(bundleId, largestBundleIdReceived) > 0);
    }

    public ClientWindow getClientWindow() {
        return this.clientWindow;
    }

    public ClientSecurity getClientSecurity() {
        return this.client;
    }
}
