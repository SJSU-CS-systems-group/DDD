package net.discdd.client.bundlesecurity;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;

import android.util.Base64;

import net.discdd.bundlesecurity.SecurityUtils;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class ClientSecurity {

    private static final Logger logger = Logger.getLogger(ClientSecurity.class.getName());

    private static ClientSecurity singleClientInstance = null;

    // Used to store in local signal protocol store so can be same across devices
    private SignalProtocolAddress ourAddress;
    // Identity Key Pairs public key is used as the Client ID
    private IdentityKeyPair ourIdentityKeyPair;
    private ECKeyPair ourBaseKey;

    private IdentityKey theirIdentityKey;
    private ECPublicKey theirSignedPreKey;
    private Optional<ECPublicKey> theirOneTimePreKey;
    private ECPublicKey theirRatchetKey;

    private SessionCipher cipherSession;
    private SignalProtocolStore clientProtocolStore;

    private String clientID;
    private Path clientRootPath;

    private ClientSecurity(int deviceID, Path clientRootPath, Path serverKeyPath) throws InvalidKeyException,
            IOException, NoSuchAlgorithmException {
        var clientKeyPath = clientRootPath.resolve("Client_Keys");

        // Read Server Keys from specified directory
        InitializeServerKeysFromFiles(serverKeyPath);

        try {
            loadKeysfromFiles(clientKeyPath);
            logger.log(FINE, "[Sec]: Using Existing Keys");
            System.out.println("[Sec]: Using Existing Keys");
        } catch (IOException | InvalidKeyException e) {
            logger.log(WARNING, "[Sec]: Error Loading Keys from files, generating new keys instead");
            // Create Client's Key pairs
            ECKeyPair identityKeyPair = Curve.generateKeyPair();
            ourIdentityKeyPair = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                     identityKeyPair.getPrivateKey());

            ourBaseKey = Curve.generateKeyPair();
            // Write generated keys to files
            writeKeysToFiles(clientKeyPath, true);
        }

        clientProtocolStore = SecurityUtils.createInMemorySignalProtocolStore();

        // Create Client ID
        clientID = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().getPublicKey().serialize());
        ourAddress = new SignalProtocolAddress(clientID, deviceID);
        theirOneTimePreKey = Optional.<ECPublicKey>absent();
        this.clientRootPath = clientRootPath;

        // Create Client Cipher
        createCipher();
    }

    private Path[] writeKeysToFiles(Path path, boolean writePvt) throws IOException {
        /* Create Directory if it does not exist */
        path.toFile().mkdirs();
        Path[] identityKeyPaths =
                { path.resolve(SecurityUtils.CLIENT_IDENTITY_KEY), path.resolve(SecurityUtils.CLIENT_BASE_KEY),
                        path.resolve(SecurityUtils.SERVER_IDENTITY_KEY) };

        if (writePvt) {
            Files.write(path.resolve("clientIdentity.pvt"), ourIdentityKeyPair.getPrivateKey().serialize());
            Files.write(path.resolve("clientBase.pvt"), ourBaseKey.getPrivateKey().serialize());
        }

        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), identityKeyPaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourBaseKey.getPublicKey(), identityKeyPaths[1]);
        SecurityUtils.createEncodedPublicKeyFile(theirIdentityKey.getPublicKey(), identityKeyPaths[2]);
        return identityKeyPaths;
    }

    private void loadKeysfromFiles(Path clientKeyPath) throws IOException, InvalidKeyException {
        byte[] identityKeyPvt = Files.readAllBytes(clientKeyPath.resolve("clientIdentity.pvt"));
        byte[] identityKeyPub = SecurityUtils.decodePublicKeyfromFile(clientKeyPath.resolve("clientIdentity.pub"));

        IdentityKey identityPublicKey = new IdentityKey(identityKeyPub, 0);
        ECPrivateKey identityPrivateKey = Curve.decodePrivatePoint(identityKeyPvt);

        ourIdentityKeyPair = new IdentityKeyPair(identityPublicKey, identityPrivateKey);

        byte[] baseKeyPvt = Files.readAllBytes(clientKeyPath.resolve("clientBase.pvt"));
        byte[] baseKeyPub = SecurityUtils.decodePublicKeyfromFile(clientKeyPath.resolve("clientBase.pub"));

        ECPublicKey basePublicKey = Curve.decodePoint(baseKeyPub, 0);
        ECPrivateKey basePrivateKey = Curve.decodePrivatePoint(baseKeyPvt);

        ourBaseKey = new ECKeyPair(basePublicKey, basePrivateKey);
    }

    private void InitializeServerKeysFromFiles(Path path) throws InvalidKeyException, IOException {
        byte[] serverIdentityKey =
                SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_IDENTITY_KEY));
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);

        byte[] serverSignedPreKey =
                SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_SIGNEDPRE_KEY));
        theirSignedPreKey = Curve.decodePoint(serverSignedPreKey, 0);

        byte[] serverRatchetKey = SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_RATCHET_KEY));
        theirRatchetKey = Curve.decodePoint(serverRatchetKey, 0);
    }

    private void initializeRatchet(SessionState clientSessionState) throws InvalidKeyException {
        AliceSignalProtocolParameters parameters = AliceSignalProtocolParameters.newBuilder().setOurBaseKey(ourBaseKey)
                .setOurIdentityKey(ourIdentityKeyPair).setTheirOneTimePreKey(Optional.<ECPublicKey>absent())
                .setTheirRatchetKey(theirRatchetKey).setTheirSignedPreKey(theirSignedPreKey)
                .setTheirIdentityKey(theirIdentityKey).create();
        RatchetingSession.initializeSession(clientSessionState, parameters);
    }

    private void updateSessionRecord() {
        String sessionStorePath = clientRootPath.resolve(SecurityUtils.SESSION_STORE_FILE).toString();

        try (FileOutputStream stream = new FileOutputStream(sessionStorePath)) {
            SessionRecord clientSessionRecord = clientProtocolStore.loadSession(ourAddress);
            stream.write(clientSessionRecord.serialize());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createCipher() throws InvalidKeyException {
        var sessionStorePath = clientRootPath.resolve(SecurityUtils.SESSION_STORE_FILE);
        SessionRecord clientSessionRecord = null;

        try {
            byte[] sessionStoreBytes = Files.readAllBytes(sessionStorePath);
            clientSessionRecord = new SessionRecord(sessionStoreBytes);
        } catch (IOException e) {
            logger.log(WARNING,
                       "Error Reading Session record from " + sessionStorePath + "\nCreating New Session Record!");
            clientSessionRecord = new SessionRecord();
            initializeRatchet(clientSessionRecord.getSessionState());
        }

        clientProtocolStore.storeSession(ourAddress, clientSessionRecord);
        cipherSession = new SessionCipher(clientProtocolStore, ourAddress);
        updateSessionRecord();
    }

    private void createSignature(byte[] fileContents, Path signedFilePath) throws IOException, InvalidKeyException {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = Base64.encodeToString(signedData, Base64.URL_SAFE | Base64.NO_WRAP);
        Files.write(signedFilePath, encodedSignature.getBytes());
    }

    /* Encrypts and creates a file for the BundleID */

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID) throws GeneralSecurityException, InvalidKeyException {
        byte[] agreement =
                Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());

        String secretKey = Base64.encodeToString(agreement, Base64.URL_SAFE | Base64.NO_WRAP);

        return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID);
    }

    /* Add Headers (Identity, Base Key & Bundle ID) to Bundle Path */

    /* Initialize or get previous client Security Instance */
    public static synchronized ClientSecurity initializeInstance(int deviceID, Path clientRootPath,
                                                                 Path serverKeyPath) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException {
        if (singleClientInstance == null) {
            singleClientInstance = new ClientSecurity(deviceID, clientRootPath, serverKeyPath);
        } else {
            logger.log(FINE, "[Sec]: Client Security Instance is already initialized!");
        }

        return singleClientInstance;
    }

    public static synchronized ClientSecurity getInstance() throws IllegalStateException {
        if (singleClientInstance == null) {
            throw new IllegalStateException("[Sec]: Client Security Session is not initialized!");
        }
        return singleClientInstance;
    }

    /* Encrypts File and creates signature for plain text */
    public Path[] encrypt(final Path toBeEncPath, final Path encPath, final String bundleID) throws IOException,
            InvalidKeyException {
        final var bundlePath = encPath.resolve(bundleID);
        final var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);
        final var signPath = bundlePath.resolve(SecurityUtils.SIGNATURE_DIR);
        List<Path> returnPaths = new ArrayList<>();

        /* Create Directory if it does not exist */
        bundlePath.toFile().mkdirs();
        payloadPath.toFile().mkdirs();
        signPath.toFile().mkdirs();

        try (DataInputStream inputStream = new DataInputStream(new FileInputStream(toBeEncPath.toFile()))) {
            byte[] chunk = new byte[SecurityUtils.CHUNKSIZE];
            int len;
            for (int i = 1; (len = inputStream.read(chunk)) != -1; i++) {
                var payloadFilePath = payloadPath.resolve(SecurityUtils.PAYLOAD_FILENAME + i);
                var signFilePath =
                        signPath.resolve(SecurityUtils.PAYLOAD_FILENAME + i + SecurityUtils.SIGNATURE_FILENAME);

                /* if we got a partial chunk make a new chunk with the exact size */
                if (chunk.length != len) chunk = Arrays.copyOf(chunk, len);

                /* Create Signature with plaintext*/
                createSignature(chunk, signFilePath);
                /* Encrypt File */
                CiphertextMessage cipherText = cipherSession.encrypt(chunk);
                updateSessionRecord();
                Files.write(payloadFilePath, cipherText.serialize());
            }
        }

        /* Create Bundle ID File */
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        Files.write(bundleIDPath, bundleID.getBytes());

        /* Write Keys to Bundle directory */
        Path[] identityKeyPaths = writeKeysToFiles(bundlePath, false);

        returnPaths.add(payloadPath);
        returnPaths.add(signPath);

        returnPaths.addAll(Arrays.asList(identityKeyPaths));
        return returnPaths.toArray(new Path[returnPaths.size()]);
    }

    public void decrypt(Path bundlePath, Path decryptedPath) throws IOException, InvalidMessageException,
            LegacyMessageException, NoSessionException, DuplicateMessageException, InvalidKeyException {
        var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);
        var signPath = bundlePath.resolve(SecurityUtils.SIGNATURE_DIR);

        String bundleID = getBundleIDFromFile(bundlePath);
        var decryptedFile = decryptedPath.resolve(bundleID + SecurityUtils.DECRYPTED_FILE_EXT);

        /* Create Directory if it does not exist */
        decryptedPath.toFile().mkdirs();

        System.out.println(decryptedFile);
        int fileCount = payloadPath.toFile().list().length;

        for (int i = 1; i <= fileCount; ++i) {
            String payloadName = SecurityUtils.PAYLOAD_FILENAME + i;
            var signatureFile = signPath.resolve(payloadName + SecurityUtils.SIGNATURE_FILENAME);

            byte[] encryptedData = Files.readAllBytes(payloadPath.resolve(payloadName));
            byte[] serverDecryptedMessage = cipherSession.decrypt(new SignalMessage(encryptedData));
            updateSessionRecord();

            Files.write(decryptedFile, serverDecryptedMessage, StandardOpenOption.APPEND, StandardOpenOption.CREATE);

            logger.log(FINER, "Decrypted Size = %d\n", serverDecryptedMessage.length);

            if (SecurityUtils.verifySignature(serverDecryptedMessage, theirIdentityKey.getPublicKey(), signatureFile)) {
                logger.log(FINE, "Verified Signature!");
            } else {
                // Failed to verify sign, delete bundle and return
                logger.log(WARNING, "Invalid Signature [" + payloadName + "], Aborting bundle " + bundleID);

                if (!decryptedFile.toFile().delete()) {
                    logger.log(WARNING, "Error: Failed to delete decrypted file [%s]", decryptedFile);
                }
            }
        }

    }

    public String decryptBundleID(String encryptedBundleID) throws GeneralSecurityException, InvalidKeyException {
        byte[] agreement = null;
        byte[] bundleIDBytes = null;

        agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());

        String secretKey = Base64.encodeToString(agreement, Base64.URL_SAFE | Base64.NO_WRAP);

        bundleIDBytes = SecurityUtils.decryptAesCbcPkcs5(secretKey, encryptedBundleID);
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public String getDecryptedBundleIDFromFile(Path bundlePath) throws IOException, GeneralSecurityException,
            InvalidKeyException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        byte[] encryptedBundleID = Files.readAllBytes(bundleIDPath);
        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8));
    }

    public String getClientID() {
        return this.clientID;
    }

    public String getBundleIDFromFile(Path bundlePath) throws IOException {
        byte[] bundleIDBytes = Files.readAllBytes(bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME));
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public Path getClientRootPath() {
        return clientRootPath;
    }

}
