package net.discdd.server.bundlesecurity;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
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
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
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
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ServerSecurity {

    private static final String DEFAULT_SERVER_NAME = "Bundle Server";
    private static final int ServerDeviceID = 0;
    private static final Logger logger = Logger.getLogger(ServerSecurity.class.getName());
    private static ServerSecurity singleServerInstance = null;
    private final HashMap<String, ClientSession> clientMap;
    private SignalProtocolAddress ourAddress;
    private IdentityKeyPair ourIdentityKeyPair;
    private ECKeyPair ourSignedPreKey;
    private Optional<ECKeyPair> ourOneTimePreKey;
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
    private ServerSecurity(Path serverRootPath) {
        clientMap = new HashMap<>();
        var serverKeyPath = serverRootPath.resolve(SecurityUtils.SERVER_KEY_PATH);

        try {
            loadKeysfromFiles(serverKeyPath);
            this.serverRootPath = serverRootPath;
            serverProtocolStore = SecurityUtils.createInMemorySignalProtocolStore();

            String name = DEFAULT_SERVER_NAME;
            name = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
            ourAddress = new SignalProtocolAddress(name, ServerDeviceID);
            ourOneTimePreKey = Optional.absent();

            clientRootPath = serverRootPath.resolve("Clients");
            clientRootPath.toFile().mkdirs();
        } catch (Exception e) {
//            logger.log(SEVERE,(e.getMessage());

            e.printStackTrace();
            logger.log(SEVERE, String.format(
                    "Error loading server keys. Ensure the following key files exist in your application.yml's " +
                            "{bundle-server.bundle-security.server-serverkeys-path} path: %s\n" +
                            "server_identity.pub, serverIdentity.pvt, server_signed_pre.pub, serverSignedPreKey.pvt, " +
                            "server_ratchet.pub, serverRatchetKey.pvt\n",
                    serverKeyPath));
            throw new RuntimeException("Bad keys");
        }
        //     try {
        //     // TODO: Load protocol store from files(serverProtocolStore)
        //         loadKeysfromFiles(serverKeyPath);
        //         logger.log(SEVERE,"[ServerSecurity]: Using Existing Keys");
        //     } catch (InvalidKeyException | IOException | EncodingException e) {
        //         logger.log(SEVERE,"[ServerSecurity]: Error Loading Keys from files, generating new keys instead");

        // ECKeyPair identityKeyPair       = Curve.generateKeyPair();
        // ourIdentityKeyPair              = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
        //                                                             identityKeyPair.getPrivateKey());
        // ourSignedPreKey                 = Curve.generateKeyPair();
        // ourRatchetKey                   = ourSignedPreKey;

        //     try {
        //         writeKeysToFiles(serverKeyPath, true);
        //     } catch (IOException | EncodingException exception) {
        //         throw new ServerIntializationException("Failed to write keys to Files:"+exception);
        //     }
        // }

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
        byte[] identityKey = SecurityUtils.decodePrivateKeyFromFile(
                serverKeyPath.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY));
        ourIdentityKeyPair = new IdentityKeyPair(identityKey);

        byte[] signedPreKeyPvt = SecurityUtils.decodePrivateKeyFromFile(
                serverKeyPath.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY));
        byte[] signedPreKeyPub =
                SecurityUtils.decodePublicKeyfromFile(serverKeyPath.resolve(SecurityUtils.SERVER_SIGNEDPRE_KEY));

        ECPublicKey signedPreKeyPublicKey = Curve.decodePoint(signedPreKeyPub, 0);
        ECPrivateKey signedPreKeyPrivateKey = Curve.decodePrivatePoint(signedPreKeyPvt);

        ourSignedPreKey = new ECKeyPair(signedPreKeyPublicKey, signedPreKeyPrivateKey);

        byte[] ratchetKeyPvt =
                SecurityUtils.decodePrivateKeyFromFile(serverKeyPath.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY));
        byte[] ratchetKeyPub =
                SecurityUtils.decodePublicKeyfromFile(serverKeyPath.resolve(SecurityUtils.SERVER_RATCHET_KEY));

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

        Path[] serverKeypaths =
                { path.resolve(SecurityUtils.SERVER_IDENTITY_KEY), path.resolve(SecurityUtils.SERVER_SIGNEDPRE_KEY),
                        path.resolve(SecurityUtils.SERVER_RATCHET_KEY) };

        if (writePvt) {
            writePrivateKeys(path);
        }
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), serverKeypaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourSignedPreKey.getPublicKey(), serverKeypaths[1]);
        SecurityUtils.createEncodedPublicKeyFile(ourRatchetKey.getPublicKey(), serverKeypaths[2]);
        return serverKeypaths;
    }

    private void updateSessionRecord(ClientSession clientSession) {
        String sessionStorePath =
                clientRootPath.resolve(Path.of(clientSession.getClientID(), SecurityUtils.SESSION_STORE_FILE))
                        .toString();
        SessionRecord clientSessionRecord = null;

        try (FileOutputStream stream = new FileOutputStream(sessionStorePath)) {
            clientSessionRecord = serverProtocolStore.loadSession(clientSession.clientProtocolAddress);
            stream.write(clientSessionRecord.serialize());
        } catch (IOException e) {
            logger.log(SEVERE, "Update Session Record", e);
        }
    }

    /* Retrieves or Initializes Client Session on the server
     * Parameters:
     *      clientKeyPath:   Path where the client's Keys are stored
     * Exceptions:
     *      IOException:    Thrown if keys cannot be written to provided path
     */
    private ClientSession getClientSession(Path clientKeyPath, String clientID) throws InvalidKeyException,
            IOException {
        ClientSession clientSession = new ClientSession();
        var clientDataPath = clientRootPath.resolve(clientID);

        clientDataPath.toFile().mkdirs();
        logger.log(FINE, "[ServerSecurity]:Client Data Path = " + clientDataPath);
        try {
            Files.copy(clientKeyPath.resolve(SecurityUtils.CLIENT_IDENTITY_KEY),
                       clientDataPath.resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
            Files.copy(clientKeyPath.resolve(SecurityUtils.CLIENT_BASE_KEY),
                       clientDataPath.resolve(SecurityUtils.CLIENT_BASE_KEY));
        } catch (IOException e) {
            logger.log(SEVERE,
                       "[ServerSecurity] INFO: Client Keys already exist Client Data Path for client ID " + clientID,
                       e);
            logger.log(SEVERE, "[SEC] INFO: Client Keys already exist Client Data Path for client ID " + clientID);
        }

        initializeClientKeysFromFiles(clientDataPath, clientSession);

        var sessionStorePath = clientDataPath.resolve(SecurityUtils.SESSION_STORE_FILE);
        SessionRecord clientSessionRecord = null;

        try {
            byte[] sessionStoreBytes = Files.readAllBytes(sessionStorePath);
            clientSessionRecord = new SessionRecord(sessionStoreBytes);
        } catch (IOException e) {
            logger.log(SEVERE, "[ServerSecurity]: Error Reading Session record from " + sessionStorePath +
                    "\nCreating New Session Record!");
            logger.log(SEVERE, "[ServerSecurity]: Error Reading Session record from " + sessionStorePath +
                    "\nCreating New Session Record!");
            clientSessionRecord = new SessionRecord();
            initializeRatchet(clientSessionRecord.getSessionState(), clientSession);
        }

        clientSession.clientProtocolAddress = new SignalProtocolAddress(clientID, clientID.hashCode());

        serverProtocolStore.storeSession(clientSession.clientProtocolAddress, clientSessionRecord);

        clientSession.cipherSession = new SessionCipher(serverProtocolStore, clientSession.clientProtocolAddress);
        updateSessionRecord(clientSession);

        clientMap.put(clientID, clientSession);

        return clientSession;
    }

    private void initializeClientKeysFromFiles(Path path, ClientSession clientSession) throws IOException,
            InvalidKeyException {
        byte[] clientIdentityKey =
                SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
        clientSession.IdentityKey = new IdentityKey(clientIdentityKey, 0);

        byte[] clientBaseKey = SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.CLIENT_BASE_KEY));
        clientSession.BaseKey = Curve.decodePoint(clientBaseKey, 0);
    }

    private void initializeRatchet(SessionState serverSessionState, ClientSession clientSession) throws InvalidKeyException {
        BobSignalProtocolParameters parameters =
                BobSignalProtocolParameters.newBuilder().setOurRatchetKey(ourRatchetKey)
                        .setOurSignedPreKey(ourSignedPreKey).setOurOneTimePreKey(Optional.absent())
                        .setOurIdentityKey(ourIdentityKeyPair).setTheirIdentityKey(clientSession.IdentityKey)
                        .setTheirBaseKey(clientSession.BaseKey).create();
        RatchetingSession.initializeSession(serverSessionState, parameters);
    }

    private ClientSession getClientSession(String clientID) {
        if (clientMap.containsKey(clientID)) {
            return clientMap.get(clientID);
        } else {
            // TODO: Change to log
            logger.log(SEVERE, "[ServerSecurity]:Key[ " + clientID + " ] NOT found!");
        }
        return null;
    }

    private ClientSession getClientSessionFromFile(Path clientKeyPath) throws IOException, NoSuchAlgorithmException,
            InvalidKeyException {
        ClientSession client = null;
        String clientID = SecurityUtils.getClientID(clientKeyPath);
        client = getClientSession(clientKeyPath, clientID);
        return client;
    }

    private void createSignature(byte[] fileContents, Path signedFilePath) throws InvalidKeyException, IOException {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = Base64.getUrlEncoder().encodeToString(signedData);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath.toFile())) {
            stream.write(encodedSignature.getBytes());
        }
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

    private String getsharedSecret(String clientID) throws InvalidKeyException, InvalidClientIDException {
        /* get Client Session */
        ClientSession client = getClientSession(clientID);
        if (client == null) {
            throw new InvalidClientIDException("Failed to get client [" + clientID + "] ",
                                               new Throwable("Client not found"));
        }

        byte[] agreement =
                Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    public void decrypt(Path bundlePath, Path decryptedPath) throws IOException, GeneralSecurityException,
            InvalidKeyException, InvalidMessageException, LegacyMessageException, NoSessionException,
            DuplicateMessageException {
        ClientSession client = getClientSessionFromFile(bundlePath);
        var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);
        var signPath = bundlePath.resolve(SecurityUtils.SIGNATURE_DIR);

        String bundleID = getBundleIDFromFile(bundlePath);
        Path decryptedFile = decryptedPath.resolve(bundleID + SecurityUtils.DECRYPTED_FILE_EXT);

        /* Create Directory if it does not exist */
        decryptedPath.toFile().mkdirs();

        logger.log(INFO, decryptedFile.toString());
        int fileCount = payloadPath.toFile().list().length;

        for (int i = 1; i <= fileCount; ++i) {
            String payloadName = SecurityUtils.PAYLOAD_FILENAME + i;
            var signatureFile = signPath.resolve(payloadName + SecurityUtils.SIGNATURE_FILENAME);

            byte[] encryptedData = Files.readAllBytes(payloadPath.resolve(payloadName));
            byte[] serverDecryptedMessage = client.cipherSession.decrypt(new SignalMessage(encryptedData));
            updateSessionRecord(client);
            try (FileOutputStream stream = new FileOutputStream(decryptedFile.toFile(), true)) {
                stream.write(serverDecryptedMessage);
            }
            logger.log(FINE, "[ServerSecurity]:Decrypted Size = %d", serverDecryptedMessage.length);

            if (SecurityUtils.verifySignature(serverDecryptedMessage, client.IdentityKey.getPublicKey(),
                                              signatureFile)) {
                logger.log(WARNING, "[ServerSecurity]:Verified Signature!");
            } else {
                // Failed to verify sign, delete bundle and return
                logger.log(WARNING,
                           "[ServerSecurity]:Invalid Signature [" + payloadName + "], Aborting bundle " + bundleID);

                try {
                    Files.deleteIfExists(decryptedFile);
                } catch (Exception e) {
                    logger.log(SEVERE, "[ServerSecurity] Error: Failed to delete decrypted file [%s]", decryptedFile);
                    logger.log(SEVERE, "Error" + e);
                }
            }
        }
    }

    public Path[] encrypt(Path toBeEncPath, Path encPath, String bundleID, String clientID) throws IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidClientSessionException {
        /* get Client Session */
        ClientSession client = getClientSessionFromFile(clientRootPath.resolve(clientID));
        if (client == null) {
            throw new InvalidClientSessionException("Failed to get client [" + clientID + "]",
                                                    new Throwable("Client not found"));
        }

        var bundlePath = encPath.resolve(bundleID);
        var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);
        var signPath = bundlePath.resolve(SecurityUtils.SIGNATURE_DIR);
        List<Path> returnPaths = new ArrayList<>();
        int len = 0;

        /* Create Directory if it does not exist */
        bundlePath.toFile().mkdirs();
        payloadPath.toFile().mkdirs();
        signPath.toFile().mkdirs();

        DataInputStream inputStream = new DataInputStream(new FileInputStream(toBeEncPath.toFile()));
        byte[] chunk = new byte[SecurityUtils.CHUNKSIZE];

        for (int i = 1; (len = inputStream.read(chunk)) != -1; i++) {
            var encBundlePath = payloadPath.resolve(SecurityUtils.PAYLOAD_FILENAME + i);
            var signBundlePath =
                    signPath.resolve(SecurityUtils.PAYLOAD_FILENAME + i + SecurityUtils.SIGNATURE_FILENAME);

            if (chunk.length != len) {
                chunk = Arrays.copyOf(chunk, len);
            }

            /* Create Signature with plaintext*/
            createSignature(chunk, signBundlePath);
            /* Encrypt File */
            CiphertextMessage cipherText = client.cipherSession.encrypt(chunk);
            updateSessionRecord(client);

            Files.write(encBundlePath, cipherText.serialize());
        }
        inputStream.close();

        /* Create Encryption Headers */
        Path[] clientKeyPaths = createEncryptionHeader(encPath, bundleID, client);

        returnPaths.add(payloadPath);
        returnPaths.add(signPath);

        Collections.addAll(returnPaths, clientKeyPaths);

        return returnPaths.toArray(new Path[returnPaths.size()]);
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

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID, ClientSession client) throws InvalidKeyException,
            GeneralSecurityException {
        String sharedSecret = null;
        sharedSecret = getsharedSecret(client);
        return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
    }

    public String encryptBundleID(String bundleID, String clientID) throws GeneralSecurityException,
            InvalidKeyException, InvalidClientIDException {
        String sharedSecret = null;
        sharedSecret = getsharedSecret(clientID);

        return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
    }

    public void createBundleIDFile(String bundleID, ClientSession client, Path bundlePath) throws IOException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        Files.write(bundlePath, bundleID.getBytes());
    }

    public String decryptBundleID(String encryptedBundleID, String clientID) throws InvalidClientIDException,
            InvalidKeyException, GeneralSecurityException {
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

    public int isNewerBundle(Path bundlePath, String lastBundleID) throws IOException, GeneralSecurityException,
            InvalidKeyException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        byte[] encryptedBundleID = Files.readAllBytes(bundleIDPath);
        String receivedBundleID, latestBundleID;

        byte[] clientIdentityKeyBytes =
                SecurityUtils.decodePublicKeyfromFile(bundlePath.resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
        IdentityKey clientIdentityKey = new IdentityKey(clientIdentityKeyBytes, 0);

        String sharedSecret = getsharedSecret(clientIdentityKey.getPublicKey());

        byte[] bundleIDbytes =
                SecurityUtils.decryptAesCbcPkcs5(sharedSecret, new String(encryptedBundleID, StandardCharsets.UTF_8));
        receivedBundleID = new String(bundleIDbytes, StandardCharsets.UTF_8);
        bundleIDbytes = SecurityUtils.decryptAesCbcPkcs5(sharedSecret, lastBundleID);
        latestBundleID = new String(bundleIDbytes, StandardCharsets.UTF_8);

        return BundleIDGenerator.compareBundleIDs(receivedBundleID, latestBundleID, BundleIDGenerator.UPSTREAM);
    }

    public String getServerId() throws NoSuchAlgorithmException {
        return SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
    }

}
