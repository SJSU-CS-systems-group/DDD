package net.discdd.client.bundlesecurity;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.pathutils.ClientPaths;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

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

    private int deviceID;
    private String clientID;
    private ClientPaths clientPaths;

    ClientSecurity(int deviceID, ClientPaths clientPaths) throws InvalidKeyException, IOException,
            NoSuchAlgorithmException {
        this.clientPaths = clientPaths;

        // Read Server Keys from specified directory
        InitializeServerKeysFromFiles(clientPaths.serverKeyPath);

        try {
            loadKeysfromFiles(clientPaths.clientKeyPath);
            logger.log(FINE, "[Sec]: Using Existing Keys");
        } catch (IOException | InvalidKeyException e) {
            logger.log(WARNING, "[Sec]: Error Loading Keys from files, generating new keys instead");
            // Create Client's Key pairs
            ECKeyPair identityKeyPair = Curve.generateKeyPair();
            ourIdentityKeyPair = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                     identityKeyPair.getPrivateKey());

            ourBaseKey = Curve.generateKeyPair();
            // Write generated keys to files
            writeKeysToFiles(clientPaths.clientKeyPath, true);
        }

        clientProtocolStore = SecurityUtils.createInMemorySignalProtocolStore();

        // Create Client ID
        clientID = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().getPublicKey().serialize());
        ourAddress = new SignalProtocolAddress(clientID, deviceID);
        theirOneTimePreKey = Optional.<ECPublicKey>absent();
        this.deviceID = deviceID;
        // Create Client Cipher
        createCipher();
    }

    private Path[] writeKeysToFiles(Path path, boolean writePvt) throws IOException {
        /* Create Directory if it does not exist */
        path.toFile().mkdirs();
        Path[] identityKeyPaths = { path.resolve(SecurityUtils.CLIENT_IDENTITY_KEY),
                                    path.resolve(SecurityUtils.CLIENT_BASE_KEY),
                                    path.resolve(SecurityUtils.SERVER_IDENTITY_KEY) };

        if (writePvt) {
            Files.write(path.resolve(SecurityUtils.CLIENT_IDENTITY_PRIVATE_KEY),
                        ourIdentityKeyPair.getPrivateKey().serialize());
            Files.write(path.resolve(SecurityUtils.CLIENT_BASE_PRIVATE_KEY), ourBaseKey.getPrivateKey().serialize());
        }
        DDDPEMEncoder.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), identityKeyPaths[0]);
        DDDPEMEncoder.createEncodedPublicKeyFile(ourBaseKey.getPublicKey(), identityKeyPaths[1]);
        DDDPEMEncoder.createEncodedPublicKeyFile(theirIdentityKey.getPublicKey(), identityKeyPaths[2]);
        return identityKeyPaths;
    }

    private void loadKeysfromFiles(Path clientKeyPath) throws IOException, InvalidKeyException {
        byte[] identityKeyPvt = Files.readAllBytes(clientKeyPath.resolve(SecurityUtils.CLIENT_IDENTITY_PRIVATE_KEY));
        byte[] identityKeyPub =
                DDDPEMEncoder.decodePublicKeyfromFile(clientKeyPath.resolve(SecurityUtils.CLIENT_IDENTITY_KEY));

        IdentityKey identityPublicKey = new IdentityKey(identityKeyPub, 0);
        ECPrivateKey identityPrivateKey = Curve.decodePrivatePoint(identityKeyPvt);

        ourIdentityKeyPair = new IdentityKeyPair(identityPublicKey, identityPrivateKey);

        byte[] baseKeyPvt = Files.readAllBytes(clientKeyPath.resolve(SecurityUtils.CLIENT_BASE_PRIVATE_KEY));
        byte[] baseKeyPub = DDDPEMEncoder.decodePublicKeyfromFile(clientKeyPath.resolve(SecurityUtils.CLIENT_BASE_KEY));

        ECPublicKey basePublicKey = Curve.decodePoint(baseKeyPub, 0);
        ECPrivateKey basePrivateKey = Curve.decodePrivatePoint(baseKeyPvt);

        ourBaseKey = new ECKeyPair(basePublicKey, basePrivateKey);
    }

    private void InitializeServerKeysFromFiles(Path path) throws InvalidKeyException, IOException {
        byte[] serverIdentityKey =
                DDDPEMEncoder.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_IDENTITY_KEY));
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);

        byte[] serverSignedPreKey =
                DDDPEMEncoder.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY));
        theirSignedPreKey = Curve.decodePoint(serverSignedPreKey, 0);

        byte[] serverRatchetKey = DDDPEMEncoder.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_RATCHET_KEY));
        theirRatchetKey = Curve.decodePoint(serverRatchetKey, 0);
    }

    private void initializeRatchet(SessionState clientSessionState) throws InvalidKeyException {
        AliceSignalProtocolParameters parameters = AliceSignalProtocolParameters.newBuilder()
                .setOurBaseKey(ourBaseKey)
                .setOurIdentityKey(ourIdentityKeyPair)
                .setTheirOneTimePreKey(Optional.<ECPublicKey>absent())
                .setTheirRatchetKey(theirRatchetKey)
                .setTheirSignedPreKey(theirSignedPreKey)
                .setTheirIdentityKey(theirIdentityKey)
                .create();
        RatchetingSession.initializeSession(clientSessionState, parameters);
    }

    private void updateSessionRecord() {
        try (FileOutputStream stream = new FileOutputStream(clientPaths.sessionStorePath.toString())) {
            SessionRecord clientSessionRecord = clientProtocolStore.loadSession(ourAddress);
            stream.write(clientSessionRecord.serialize());
        } catch (IOException e) {
            logger.log(SEVERE, "Error Writing Session record to " + clientPaths.sessionStorePath, e);
        }
    }

    private void createCipher() throws InvalidKeyException {
        SessionRecord clientSessionRecord = null;

        try {
            byte[] sessionStoreBytes = Files.readAllBytes(clientPaths.sessionStorePath);
            clientSessionRecord = new SessionRecord(sessionStoreBytes);
        } catch (IOException e) {
            logger.log(WARNING,
                       "Error Reading Session record from " + clientPaths.sessionStorePath +
                               "\nCreating New Session Record!");
            clientSessionRecord = new SessionRecord();
            initializeRatchet(clientSessionRecord.getSessionState());
        }

        clientProtocolStore.storeSession(ourAddress, clientSessionRecord);
        cipherSession = new SessionCipher(clientProtocolStore, ourAddress);
        updateSessionRecord();
    }

    /* Encrypts and creates a file for the BundleID */

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID) throws GeneralSecurityException, InvalidKeyException {
        byte[] agreement =
                Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());

        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);

        return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID, true);
    }

    /* Add Headers (Identity, Base Key & Bundle ID) to Bundle Path */

    /* Initialize or get previous client Security Instance */
    public static synchronized ClientSecurity initializeInstance(int deviceID, ClientPaths clientPaths) throws
            IOException, NoSuchAlgorithmException, InvalidKeyException {
        if (singleClientInstance == null) {
            singleClientInstance = new ClientSecurity(deviceID, clientPaths);
        } else {
            logger.log(FINE, "[Sec]: Client Security Instance is already initialized!");
        }

        return singleClientInstance;
    }

    /* Be Careful When you call This  */
    public static synchronized void resetInstance() throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        var deviceID = singleClientInstance.getDeviceId();
        var clientPaths = singleClientInstance.getClientPaths();
        singleClientInstance = null;
        initializeInstance(deviceID, clientPaths);
        logger.log(FINE, "[Sec]: Client Security Instance Has been reset!");
    }

    public static synchronized ClientSecurity getInstance() throws IllegalStateException {
        if (singleClientInstance == null) {
            throw new IllegalStateException("[Sec]: Client Security Session is not initialized!");
        }
        return singleClientInstance;
    }

    /* Encrypts File */
    public void encrypt(InputStream inputStream, OutputStream outputStream) throws IOException,
            InvalidMessageException {
        /* Encrypt File */
        cipherSession.encrypt(inputStream, outputStream);
        updateSessionRecord();
    }

    public void decrypt(Path bundlePath, Path decryptedPath) throws IOException, InvalidMessageException,
            NoSessionException, DuplicateMessageException, InvalidKeyException {
        var payloadPath = bundlePath.resolve(SecurityUtils.PAYLOAD_DIR);

        String bundleID = getBundleIDFromFile(bundlePath);
        var decryptedFile = decryptedPath.resolve(bundleID + SecurityUtils.DECRYPTED_FILE_EXT);

        /* Create Directory if it does not exist */
        decryptedPath.toFile().mkdirs();

        String payloadName = SecurityUtils.PAYLOAD_FILENAME;

        if (cipherSession.decrypt(payloadPath.resolve(payloadName), decryptedFile)) {
            updateSessionRecord();
        }
        logger.log(FINER, "Decrypted Size = %d\n", Files.size(decryptedPath));
    }

    public String decryptBundleID(String encryptedBundleID) throws GeneralSecurityException, InvalidKeyException {
        byte[] agreement = null;
        byte[] bundleIDBytes = null;

        agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());

        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);

        bundleIDBytes = SecurityUtils.decryptAesCbcPkcs5(secretKey, encryptedBundleID);
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public String getDecryptedBundleIDFromFile(Path bundlePath) throws IOException, GeneralSecurityException,
            InvalidKeyException {
        var bundleIDPath = bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME);
        byte[] encryptedBundleID = Files.readAllBytes(bundleIDPath);
        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8));
    }

    public byte[] getSignedTLSPub(PublicKey pubKey) throws InvalidKeyException {
        return SecurityUtils.signMessageRaw(pubKey.getEncoded(), ourIdentityKeyPair.getPrivateKey());
    }

    public String getClientID() {
        return this.clientID;
    }

    public String getBundleIDFromFile(Path bundlePath) throws IOException {
        byte[] bundleIDBytes = Files.readAllBytes(bundlePath.resolve(SecurityUtils.BUNDLEID_FILENAME));
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public Path getClientRootPath() {
        return clientPaths.bundleSecurityPath;
    }

    public ClientPaths getClientPaths() {
        return clientPaths;
    }

    public int getDeviceId() {
        return this.deviceID;
    }

    public ECPublicKey getServerPublicKey() {
        return theirIdentityKey.getPublicKey();
    }

    public ECPublicKey getClientIdentityPublicKey() {
        return ourIdentityKeyPair.getPublicKey().getPublicKey();
    }

    public ECPublicKey getClientBaseKeyPairPublicKey() {
        return ourBaseKey.getPublicKey();
    }
}
