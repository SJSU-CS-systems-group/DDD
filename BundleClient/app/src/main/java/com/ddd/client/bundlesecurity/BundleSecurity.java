package com.ddd.client.bundlesecurity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import org.apache.commons.lang3.StringUtils;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.NoSessionException;
import com.ddd.bundleclient.HelloworldActivity;
import com.ddd.bundleclient.R;
import com.ddd.bundlesecurity.SecurityExceptions;
import com.ddd.client.bundlerouting.ClientBundleGenerator;
import com.ddd.client.bundlerouting.ClientWindow;
import com.ddd.bundlerouting.WindowUtils.WindowExceptions;
import com.ddd.bundlesecurity.SecurityUtils;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.EncryptionHeader;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.Constants;
import com.ddd.utils.JarUtils;

import android.content.res.Resources;

public class BundleSecurity {

    private static final Logger logger = Logger.getLogger(BundleSecurity.class.getName());

    private ClientSecurity client = null;
    private static String RootFolder;
    private static String LARGEST_BUNDLE_ID_RECEIVED = "/Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";

    private static String BUNDLE_ID_NEXT_COUNTER = "/Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";

    private int counter = 0;

    private ClientWindow clientWindow;

    private ClientBundleGenerator clientBundleGenerator;

    private String clientId = "client0";

    public static final String bundleSecurityDir = "BundleSecurity";
    private boolean isEncryptionEnabled = true;

    private String getLargestBundleIdReceived() {
        String bundleId = "";
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(new File(RootFolder + LARGEST_BUNDLE_ID_RECEIVED)))) {
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
                new FileWriter(new File(RootFolder + BUNDLE_ID_NEXT_COUNTER)))) {
            bufferedWriter.write(String.valueOf(this.counter));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public BundleSecurity(String rootFolder) {
        RootFolder = rootFolder;

        String bundleSecurityPath = rootFolder + File.separator + bundleSecurityDir;

        String serverKeyPath = bundleSecurityPath + java.io.File.separator + "Server_Keys";

        File bundleIdNextCounter = new File(RootFolder + BUNDLE_ID_NEXT_COUNTER);

        try {
            bundleIdNextCounter.getParentFile().mkdirs();
            bundleIdNextCounter.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        File largestBundleIdReceived = new File(RootFolder + LARGEST_BUNDLE_ID_RECEIVED);

        try {
            largestBundleIdReceived.getParentFile().mkdirs();
            largestBundleIdReceived.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(bundleIdNextCounter))) {
            String line = bufferedReader.readLine();
            if (StringUtils.isNotEmpty(line)) {
                this.counter = Integer.valueOf(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        /* Initializing Security Module*/

        try {
            client = ClientSecurity.initializeInstance(1, bundleSecurityPath, serverKeyPath);
            clientBundleGenerator = ClientBundleGenerator.initializeInstance(client, rootFolder);
            clientWindow = ClientWindow.initializeInstance(5, client.getClientID(), rootFolder);
            logger.log(FINE, "Kuch Bhi");
        } catch (InvalidKeyException | SecurityExceptions.IDGenerationException | SecurityExceptions.EncodingException |
                 WindowExceptions.InvalidLength | WindowExceptions.BufferOverflow e) {
            e.printStackTrace();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void registerLargestBundleIdReceived(String bundleId) {
        logger.log(INFO, "[BS] Inside registerLargestBundleIdReceived function " + bundleId);
        try {
            clientWindow.processBundle(bundleId, client);
            logger.log(INFO, "Receive window is:");
            for (String windowBundleId : clientWindow.getWindow(client)) {
                logger.log(INFO, windowBundleId);
            }
        } catch (WindowExceptions.BufferOverflow e) {
            e.printStackTrace();
        } catch (SecurityExceptions.BundleIDCryptographyException e) {
            e.printStackTrace();
        }
    }

    public ClientBundleGenerator getClientBundleGenerator() {
        return clientBundleGenerator;
    }

    public void decryptBundleContents(UncompressedPayload bundle) {
        logger.log(INFO, "[BS] Decrypting contents of the bundle with id: " + bundle.getBundleId());
    }

    public String generateNewBundleId() throws SecurityExceptions.IDGenerationException,
            SecurityExceptions.BundleIDCryptographyException {
        return clientBundleGenerator.generateBundleID();
    }

    public UncompressedBundle encryptPayload(Payload payload, String bundleGenDirPath) {
        String bundleId = payload.getBundleId();
        logger.log(INFO, "Encrypting payload in bundleId: " + bundleId);
        logger.log(INFO, "[BS] Payload source:" + payload.getSource() + " bundle id " + bundleId);
        String[] paths;
        if (!this.isEncryptionEnabled) {
            return new UncompressedBundle(bundleId, payload.getSource(), null, null, null);
        }
        try {
            paths = client.encrypt(payload.getSource().getAbsolutePath(), bundleGenDirPath, bundleId);

            EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, new File(paths[0]));
            File source = new File(bundleGenDirPath + File.separator + bundleId);
            EncryptionHeader encHeader =
                    EncryptionHeader.builder().clientBaseKey(new File(paths[2])).clientIdentityKey(new File(paths[3]))
                            .serverIdentityKey(new File(paths[4])).build();
            return new UncompressedBundle(bundleId, source, encHeader, encryptedPayload, new File(paths[1]));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
        File decryptedPayloadJar = new File(uncompressedBundle.getSource().getAbsolutePath() + File.separator +
                                                    Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME + ".jar");
        String bundleId = "";
        if (this.isEncryptionEnabled) {

            try {
                ClientSecurity clientSecurity = ClientSecurity.getInstance();
                clientSecurity.decrypt(uncompressedBundle.getSource().getAbsolutePath(),
                                       uncompressedBundle.getSource().getAbsolutePath());

                bundleId = clientSecurity.getBundleIDFromFile(uncompressedBundle.getSource().getAbsolutePath());

                File decryptedPayload = new File(
                        uncompressedBundle.getSource().getAbsolutePath() + File.separator + bundleId + ".decrypted");
                if (decryptedPayload.exists()) {
                    decryptedPayload.renameTo(decryptedPayloadJar);
                }
            } catch (Exception e) {
                // TODO
                e.printStackTrace();
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

    public static void initializeKeyPaths(Resources resources, String rootDir) throws IOException {
        String serverKeyPath =
                rootDir + java.io.File.separator + BundleSecurity.bundleSecurityDir + java.io.File.separator +
                        "Server_Keys";

        InputStream inServerIdentity = resources.openRawResource(R.raw.server_identity);
        InputStream inServerSignedPre = resources.openRawResource(R.raw.server_signed_pre);
        InputStream inServerRatchet = resources.openRawResource(R.raw.server_ratchet);

        SecurityUtils.createDirectory(serverKeyPath);

        OutputStream outServerIdentity =
                new FileOutputStream(serverKeyPath + java.io.File.separator + "server_identity.pub");
        OutputStream outServerSignedPre =
                new FileOutputStream(serverKeyPath + java.io.File.separator + "server_signed_pre.pub");
        OutputStream outServerRatchet =
                new FileOutputStream(serverKeyPath + java.io.File.separator + "server_ratchet.pub");

        SecurityUtils.copyContent(inServerIdentity, outServerIdentity);
        inServerIdentity.close();
        outServerIdentity.close();

        SecurityUtils.copyContent(inServerSignedPre, outServerSignedPre);
        inServerSignedPre.close();
        outServerSignedPre.close();

        SecurityUtils.copyContent(inServerRatchet, outServerRatchet);
        inServerRatchet.close();
        outServerRatchet.close();
    }
}
