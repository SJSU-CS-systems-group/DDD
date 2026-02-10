package net.discdd.bundlesecurity;

import lombok.NonNull;
import net.discdd.bundlesecurity.SecurityUtils.ClientSession;
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
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static net.discdd.bundlesecurity.SecurityUtils.CLIENT_BASE_KEY;
import static net.discdd.bundlesecurity.SecurityUtils.CLIENT_IDENTITY_KEY;

public class ServerSecurity {

    private static final String DEFAULT_SERVER_NAME = "Bundle Server";
    private static final int ServerDeviceID = 0;
    private static final Logger logger = Logger.getLogger(ServerSecurity.class.getName());
    private static ServerSecurity singleServerInstance = null;
    private final HashMap<String, ClientSession> clientMap = new HashMap<>();
    private SignalProtocolAddress ourAddress;
    private IdentityKeyPair ourIdentityKeyPair;
    private ECKeyPair ourSignedPreKey;
    private ECKeyPair ourRatchetKey;
    private Path serverRootPath;
    private Path clientRootPath;
    private SignalProtocolStore serverProtocolStore;

    /* Initializes Security Module on the server
     * Parameters:
     *      serverKeyPath:   Path to store the generated Keys
     * Exceptions:
     *      IOException:    Thrown if keys cannot be written to provided path
     */
    public ServerSecurity(Path serverRootPath) {
        var serverKeyPath = serverRootPath.resolve(SecurityUtils.SERVER_KEY_PATH);

        try {
            loadKeysfromFiles(serverKeyPath);
            this.serverRootPath = serverRootPath;
            serverProtocolStore = SecurityUtils.createInMemorySignalProtocolStore();

            String name = DEFAULT_SERVER_NAME;
            name = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
            ourAddress = new SignalProtocolAddress(name, ServerDeviceID);

            clientRootPath = serverRootPath.resolve("Clients");
            clientRootPath.toFile().mkdirs();
        } catch (Exception e) {
//            logger.log(SEVERE,(e.getMessage());

            e.printStackTrace();
            logger.log(SEVERE,
                       String.format(
                               "Error loading server keys. Ensure the following key files exist in your application" +
                                       ".yml's " + "{bundle-server.bundle-security.server-serverkeys-path} path: %s\n" +
                                       "server_identity.pub, serverIdentity.pvt, server_signed_pre.pub, " +
                                       "serverSignedPreKey.pvt, " + "server_ratchet.pub, serverRatchetKey.pvt\n",
                               serverKeyPath));
            throw new RuntimeException("Bad keys");
        }
    }

    /* Initialize or get previous server Security Instance */
    synchronized public static ServerSecurity getInstance(Path serverRootPath) {
        if (singleServerInstance == null) {
            singleServerInstance = new ServerSecurity(serverRootPath);
        }

        return singleServerInstance;
    }

    /* load the previously used keys from the provided path
     * Parameters:
     *      serverKeyPath:   Path to store the generated Keys
     * Exceptions:
     *      FileNotFoundException:  Thrown if required files are not present
     *      IOException:            Thrown if keys cannot be written to provided path
     *      InvalidKeyException:    Thrown if the file has an invalid key
     */
    private void loadKeysfromFiles(Path serverKeyPath) throws IOException, InvalidKeyException {
        byte[] identityKey =
                DDDPEMEncoder.decodePrivateKeyFromFile(serverKeyPath.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY));
        ourIdentityKeyPair = new IdentityKeyPair(identityKey);

        byte[] signedPreKeyPvt =
                DDDPEMEncoder.decodePrivateKeyFromFile(serverKeyPath.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY));
        byte[] signedPreKeyPub =
                DDDPEMEncoder.decodePublicKeyfromFile(serverKeyPath.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY));

        ECPublicKey signedPreKeyPublicKey = Curve.decodePoint(signedPreKeyPub, 0);
        ECPrivateKey signedPreKeyPrivateKey = Curve.decodePrivatePoint(signedPreKeyPvt);

        ourSignedPreKey = new ECKeyPair(signedPreKeyPublicKey, signedPreKeyPrivateKey);

        byte[] ratchetKeyPvt =
                DDDPEMEncoder.decodePrivateKeyFromFile(serverKeyPath.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY));
        byte[] ratchetKeyPub =
                DDDPEMEncoder.decodePublicKeyfromFile(serverKeyPath.resolve(SecurityUtils.SERVER_RATCHET_KEY));

        ECPublicKey ratchetKeyPublicKey = Curve.decodePoint(ratchetKeyPub, 0);
        ECPrivateKey ratchetKeyPrivateKey = Curve.decodePrivatePoint(ratchetKeyPvt);

        ourRatchetKey = new ECKeyPair(ratchetKeyPublicKey, ratchetKeyPrivateKey);
    }

    /* TODO: Change to keystore */
    private void writePrivateKeys(Path path) throws IOException {
        Files.write(path.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY), ourIdentityKeyPair.serialize());
        Files.write(path.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY),
                    ourSignedPreKey.getPrivateKey().serialize());
        Files.write(path.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY), ourRatchetKey.getPrivateKey().serialize());
    }

    private Path[] writeKeysToFiles(Path path, boolean writePvt) throws IOException {
        /* Create Directory if it does not exist */
        Files.createDirectories(path);

        Path[] serverKeypaths = { path.resolve(SecurityUtils.SERVER_IDENTITY_KEY),
                                  path.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY),
                                  path.resolve(SecurityUtils.SERVER_RATCHET_KEY) };

        if (writePvt) {
            writePrivateKeys(path);
        }
        DDDPEMEncoder.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), serverKeypaths[0]);
        DDDPEMEncoder.createEncodedPublicKeyFile(ourSignedPreKey.getPublicKey(), serverKeypaths[1]);
        DDDPEMEncoder.createEncodedPublicKeyFile(ourRatchetKey.getPublicKey(), serverKeypaths[2]);
        return serverKeypaths;
    }

    private void updateSessionRecord(ClientSession clientSession) {
        String clientID = clientSession.getClientID();
        var sessionStorePath = clientRootPath.resolve(clientID).resolve(SecurityUtils.SESSION_STORE_FILE);
        var clientSessionRecord = serverProtocolStore.loadSession(clientSession.clientProtocolAddress);
        try {
            Files.write(sessionStorePath,
                        clientSessionRecord.serialize(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(SEVERE, "Couldn't update session record for " + clientID + " PROBLEMS AHEAD!!!", e);
        }
    }

    private void initializeClientKeysFromFiles(Path path, ClientSession clientSession) throws IOException,
            InvalidKeyException {
        byte[] clientIdentityKey;
        try {
            String clientIdentityKeyBase64 =
                    DDDPEMEncoder.decodeEncryptedPublicKeyfromFile(ourIdentityKeyPair.getPrivateKey(),
                                                                   path.resolve(CLIENT_IDENTITY_KEY));
            clientIdentityKey = Base64.getUrlDecoder().decode(clientIdentityKeyBase64);
        } catch (NoSuchAlgorithmException e) {
            throw new InvalidKeyException("No such algorithm", e);
        }
        clientSession.IdentityKey = new IdentityKey(clientIdentityKey, 0);

        byte[] clientBaseKey = DDDPEMEncoder.decodePublicKeyfromFile(path.resolve(CLIENT_BASE_KEY));
        clientSession.BaseKey = Curve.decodePoint(clientBaseKey, 0);
    }

    private void initializeRatchet(SessionState serverSessionState, ClientSession clientSession) throws
            InvalidKeyException {
        BobSignalProtocolParameters parameters = BobSignalProtocolParameters.newBuilder()
                .setOurRatchetKey(ourRatchetKey)
                .setOurSignedPreKey(ourSignedPreKey)
                .setOurOneTimePreKey(Optional.absent())
                .setOurIdentityKey(ourIdentityKeyPair)
                .setTheirIdentityKey(clientSession.IdentityKey)
                .setTheirBaseKey(clientSession.BaseKey)
                .create();
        RatchetingSession.initializeSession(serverSessionState, parameters);
    }

    @NonNull
    private ClientSession getClientSession(String clientID, Path keyPathIfNeeded) throws InvalidKeyException,
            IOException {
        var clientSession = clientMap.get(clientID);
        if (clientSession != null) {
            return clientSession;
        }
        var keyPath = clientRootPath.resolve(clientID);
        SessionRecord clientSessionRecord = null;

        // Try to read an existing session store
        clientSession = new ClientSession();
        clientSession.clientProtocolAddress = new SignalProtocolAddress(clientID, clientID.hashCode());
        var sessionStorePath = keyPath.resolve(SecurityUtils.SESSION_STORE_FILE);
        if (sessionStorePath.toFile().exists()) {
            byte[] sessionStoreBytes = Files.readAllBytes(sessionStorePath);
            clientSessionRecord = new SessionRecord(sessionStoreBytes);
        } else {
            // create one from the keys if we have them
            if (keyPathIfNeeded == null) {
                throw new InvalidKeyException("Keys for " + clientID + " not found and none provided");
            }
            keyPath.toFile().mkdirs();
            Files.copy(keyPathIfNeeded.resolve(CLIENT_IDENTITY_KEY),
                       keyPath.resolve(CLIENT_IDENTITY_KEY),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(keyPathIfNeeded.resolve(CLIENT_BASE_KEY),
                       keyPath.resolve(CLIENT_BASE_KEY),
                       StandardCopyOption.REPLACE_EXISTING);
            clientSessionRecord = new SessionRecord();
            initializeClientKeysFromFiles(keyPath, clientSession);
            initializeRatchet(clientSessionRecord.getSessionState(), clientSession);
            updateSessionRecord(clientSession);
        }
        initializeClientKeysFromFiles(keyPath, clientSession);

        serverProtocolStore.storeSession(clientSession.clientProtocolAddress, clientSessionRecord);
        clientSession.cipherSession = new SessionCipher(serverProtocolStore, clientSession.clientProtocolAddress);
        clientMap.put(clientID, clientSession);

        return clientSession;
    }

    private String getsharedSecret(ClientSession client) throws InvalidKeyException {
        byte[] agreement =
                Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    private String getsharedSecret(ECPublicKey clientIdentitKey) throws InvalidKeyException {
        byte[] agreement = Curve.calculateAgreement(clientIdentitKey, ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    private String getsharedSecret(String clientID) throws InvalidKeyException, InvalidClientIDException, IOException {
        /* get Client Session */
        ClientSession client = getClientSession(clientID, null);
        if (client == null) {
            throw new InvalidClientIDException("Failed to get client [" + clientID + "]", null);
        }

        byte[] agreement =
                Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    public Path decrypt(Path bundlePath, Path decryptedPath) throws IOException, GeneralSecurityException,
            InvalidKeyException, InvalidMessageException, LegacyMessageException, NoSessionException,
            DuplicateMessageException {
        var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);

        String bundleID = getBundleIDFromFile(bundlePath);
        Path decryptedFile = decryptedPath.resolve(bundleID + SecurityUtils.DECRYPTED_FILE_EXT);

        /* Create Directory if it does not exist */
        decryptedPath.toFile().mkdirs();

        String payloadName = SecurityUtils.PAYLOAD_FILENAME;

        var clientId = SecurityUtils.getClientID(bundlePath);
        SecurityUtils.ClientSession client = getClientSession(clientId, bundlePath);

        if (client.cipherSession.decrypt(payloadPath.resolve(payloadName), decryptedFile)) {
            updateSessionRecord(client);
        } else {
            throw new GeneralSecurityException("Could not decrypt the file");
        }

        logger.log(FINE, "[ServerSecurity]:Decrypted Size = %d", Files.size(decryptedFile));

        return decryptedFile;
    }

    public void encrypt(String clientID, InputStream plaintext, OutputStream outputStream) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException {
        ClientSession client = getClientSession(clientID, null);
        client.cipherSession.encrypt(plaintext, outputStream);
        updateSessionRecord(client);
    }

    public Path[] createEncryptionHeader(Path encPath, String bundleID, ClientSession client) throws IOException {
        var bundlePath = encPath.resolve(bundleID);

        /* Create Directory if it does not exist */
        Files.createDirectories(bundlePath);
        /* Create Bundle ID File */
        createBundleIDFile(bundleID, client, bundlePath);

        /* Write Keys to Bundle directory */
        return writeKeysToFiles(bundlePath, false);
    }

    public String encryptBundleID(String bundleID, String clientID) throws GeneralSecurityException,
            InvalidKeyException, InvalidClientIDException, IOException {
        String sharedSecret = null;
        sharedSecret = getsharedSecret(clientID);

        return SecurityUtils.encryptAesCbcPkcs5Deterministic(sharedSecret, bundleID);
    }

    public String createEncryptedBundleId(String clientId, long bundleCounter, boolean downstream) throws
            InvalidClientIDException, GeneralSecurityException, InvalidKeyException, IOException {
        var bundleId = BundleIDGenerator.generateBundleID(clientId, bundleCounter, downstream);
        return encryptBundleID(bundleId, clientId);
    }

    public void createBundleIDFile(String bundleID, ClientSession client, Path bundlePath) throws IOException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        Files.write(bundleIDPath, bundleID.getBytes());
    }

    public String decryptBundleID(String encryptedBundleID, String clientID) throws InvalidClientIDException,
            InvalidKeyException, GeneralSecurityException, IOException {
        String sharedSecret = null;
        byte[] bundleBytes = null;

        sharedSecret = getsharedSecret(clientID);

        bundleBytes = SecurityUtils.decryptAesCbcPkcs5(sharedSecret, encryptedBundleID);

        return new String(bundleBytes, StandardCharsets.UTF_8);
    }

    public String getDecryptedBundleIDFromFile(Path bundlePath, String clientID) throws InvalidClientIDException,
            GeneralSecurityException, InvalidKeyException, IOException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        byte[] encryptedBundleID = Files.readAllBytes(bundleIDPath);

        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8), clientID);
    }

    public String getBundleIDFromFile(Path bundlePath) throws IOException {
        byte[] bundleIDBytes = Files.readAllBytes(bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME));
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public Path getServerRootPath() {
        return serverRootPath;
    }

    public Path getClientRootPath() {
        return clientRootPath;
    }

    public long getCounterFromBundlePath(Path bundlePath, boolean direction) throws IOException, InvalidKeyException,
            GeneralSecurityException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        byte[] encryptedBundleID = Files.readAllBytes(bundleIDPath);
        String receivedBundleID, latestBundleID;

        ServerSecurity serverSecurityInstance = ServerSecurity.getInstance(bundlePath.getParent());
        ECPrivateKey ServerPrivKey = serverSecurityInstance.getSigningKey();
        var clientIdentityKeyBase64 =
                DDDPEMEncoder.decodeEncryptedPublicKeyfromFile(ServerPrivKey, bundlePath.resolve(CLIENT_IDENTITY_KEY));
        byte[] clientIdentityKeyBytes = Base64.getUrlDecoder().decode(clientIdentityKeyBase64);
        IdentityKey clientIdentityKey = new IdentityKey(clientIdentityKeyBytes, 0);

        String sharedSecret = getsharedSecret(clientIdentityKey.getPublicKey());

        byte[] bundleIDbytes =
                SecurityUtils.decryptAesCbcPkcs5(sharedSecret, new String(encryptedBundleID, StandardCharsets.UTF_8));

        receivedBundleID = new String(bundleIDbytes, StandardCharsets.UTF_8);
        return BundleIDGenerator.getCounterFromBundleID(receivedBundleID, direction);
    }

    public String getServerId() throws NoSuchAlgorithmException {
        return SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
    }

    public IdentityKey getIdentityPublicKey() {
        return ourIdentityKeyPair.getPublicKey();
    }

    public ECPublicKey getClientIdentityPublicKey(String clientId) throws IOException, InvalidKeyException {
        return getClientSession(clientId, null).IdentityKey.getPublicKey();
    }

    public ECPublicKey getClientBaseKey(String clientId) throws IOException, InvalidKeyException {
        return getClientSession(clientId, null).BaseKey;
    }

    public ECPrivateKey getSigningKey() {
        return ourIdentityKeyPair.getPrivateKey();
    }
}
