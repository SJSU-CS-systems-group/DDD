package com.ddd.server.bundlesecurity;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import com.ddd.server.bundlesecurity.SecurityExceptions.AESAlgorithmException;
import com.ddd.server.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.server.bundlesecurity.SecurityExceptions.EncodingException;
import com.ddd.server.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.server.bundlesecurity.SecurityExceptions.InvalidClientIDException;
import com.ddd.server.bundlesecurity.SecurityExceptions.InvalidClientSessionException;
import com.ddd.server.bundlesecurity.SecurityExceptions.ServerIntializationException;
import com.ddd.server.bundlesecurity.SecurityUtils.ClientSession;

public class ServerSecurity {
    private static final String DEFAULT_SERVER_NAME = "Bundle Server";
    private static final int ServerDeviceID = 0;
    private static ServerSecurity singleServerInstance = null;

    private SignalProtocolAddress           ourAddress;
    private IdentityKeyPair                 ourIdentityKeyPair;
    private ECKeyPair                       ourSignedPreKey;
    private Optional<ECKeyPair>             ourOneTimePreKey;
    private ECKeyPair                       ourRatchetKey;
    private HashMap<String, ClientSession>  clientMap;
    private String                          serverRootPath;
    private String                          clientRootPath;
    private SignalProtocolStore             serverProtocolStore;

    /* Initializes Security Module on the server
     * Parameters:
     *      serverKeyPath:   Path to store the generated Keys
     * Exceptions:
     *      IOException:    Thrown if keys cannot be written to provided path
     */
    private ServerSecurity(String serverRootPath) throws ServerIntializationException
    {
        clientMap  = new HashMap<>();

        String serverKeyPath = serverRootPath + File.separator + SecurityUtils.SERVER_KEY_PATH;

            try {
            // TODO: Load protocol store from files(serverProtocolStore)
                loadKeysfromFiles(serverKeyPath);
                System.out.println("[Sec]: Using Existing Keys");
            } catch (InvalidKeyException | IOException | EncodingException e) {
                System.out.println("[Sec]: Error Loading Keys from files, generating new keys instead");

        ECKeyPair identityKeyPair       = Curve.generateKeyPair();
        ourIdentityKeyPair              = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                                    identityKeyPair.getPrivateKey());
        ourSignedPreKey                 = Curve.generateKeyPair();
        ourRatchetKey                   = ourSignedPreKey;
        
            try {
                writeKeysToFiles(serverKeyPath, true);
            } catch (IOException | EncodingException exception) {
                throw new ServerIntializationException("Failed to write keys to Files:"+exception);
            }
        }

        this.serverRootPath             = serverRootPath;
        serverProtocolStore             = SecurityUtils.createInMemorySignalProtocolStore();

        String name = DEFAULT_SERVER_NAME;
        try {
            name = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
        } catch (IDGenerationException e) {
            System.out.println("[SEC]:Failed to generate ID, using default value:"+name);
        }
        ourAddress                      = new SignalProtocolAddress(name, ServerDeviceID);
        ourOneTimePreKey                = Optional.<ECKeyPair>absent();

        clientRootPath = serverRootPath+File.separator+"Clients";
        SecurityUtils.createDirectory(clientRootPath);
    }

    /* load the previously used keys from the provided path
     * Parameters:
     *      serverKeyPath:   Path to store the generated Keys
     * Exceptions:
     *      FileNotFoundException:  Thrown if required files are not present
     *      IOException:            Thrown if keys cannot be written to provided path
     *      InvalidKeyException:    Thrown if the file has an invalid key
     */
    private void loadKeysfromFiles(String serverKeyPath) throws FileNotFoundException, IOException, InvalidKeyException, EncodingException
    {
        byte[] identityKey = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverIdentity.pvt");
        ourIdentityKeyPair = new IdentityKeyPair(identityKey);

        byte[] signedPreKeyPvt = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverSignedPreKey.pvt");
        byte[] signedPreKeyPub = SecurityUtils.decodePublicKeyfromFile(serverKeyPath + File.separator + SecurityUtils.SERVER_SIGNEDPRE_KEY);

        ECPublicKey signedPreKeyPublicKey = Curve.decodePoint(signedPreKeyPub, 0);
        ECPrivateKey signedPreKeyPrivateKey = Curve.decodePrivatePoint(signedPreKeyPvt);

        ourSignedPreKey = new ECKeyPair(signedPreKeyPublicKey, signedPreKeyPrivateKey);

        byte[] ratchetKeyPvt = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverRatchetKey.pvt");
        byte[] ratchetKeyPub = SecurityUtils.decodePublicKeyfromFile(serverKeyPath + File.separator + SecurityUtils.SERVER_RATCHET_KEY);

        ECPublicKey ratchetKeyPublicKey = Curve.decodePoint(ratchetKeyPub, 0);
        ECPrivateKey ratchetKeyPrivateKey = Curve.decodePrivatePoint(ratchetKeyPvt);

        ourRatchetKey = new ECKeyPair(ratchetKeyPublicKey, ratchetKeyPrivateKey);
    }

    /* TODO: Change to keystore */
    private void writePrivateKeys(String path) throws FileNotFoundException, IOException
    {
        try (FileOutputStream stream = new FileOutputStream(path + File.separator + "serverIdentity.pvt")) {
            stream.write(ourIdentityKeyPair.serialize());
        }

        try (FileOutputStream stream = new FileOutputStream(path + File.separator + "serverSignedPreKey.pvt")) {
            stream.write(ourSignedPreKey.getPrivateKey().serialize());
        }

        try (FileOutputStream stream = new FileOutputStream(path + File.separator + "serverRatchetKey.pvt")) {
            stream.write(ourRatchetKey.getPrivateKey().serialize());
        }
    }
    
    private String[] writeKeysToFiles(String path, boolean writePvt) throws IOException, EncodingException
    {
        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(path));
        
        String[] serverKeypaths = { path + File.separator + SecurityUtils.SERVER_IDENTITY_KEY,
                                    path + File.separator + SecurityUtils.SERVER_SIGNEDPRE_KEY,
                                    path + File.separator + SecurityUtils.SERVER_RATCHET_KEY};

        if (writePvt) {
          writePrivateKeys(path);
        }
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), serverKeypaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourSignedPreKey.getPublicKey(), serverKeypaths[1]);
        SecurityUtils.createEncodedPublicKeyFile(ourRatchetKey.getPublicKey(), serverKeypaths[2]);
        return serverKeypaths;
    }


    private void updateSessionRecord(ClientSession clientSession)
    {
        String sessionStorePath = clientRootPath+File.separator+clientSession.getClientID()+File.separator+SecurityUtils.SESSION_STORE_FILE;
        SessionRecord clientSessionRecord = null;

        try (FileOutputStream stream = new FileOutputStream(sessionStorePath)) {
            clientSessionRecord = serverProtocolStore.loadSession(clientSession.clientProtocolAddress);
            stream.write(clientSessionRecord.serialize());
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    /* Retreives or Initializes Client Session on the server
     * Parameters:
     *      clientKeyPath:   Path where the client's Keys are stored
     * Exceptions:
     *      IOException:    Thrown if keys cannot be written to provided path
     */
    private ClientSession getClientSession(String clientKeyPath, String clientID) throws InvalidKeyException, EncodingException
    {
        ClientSession clientSession = new ClientSession();
        String clientDataPath = clientRootPath+File.separator+clientID;

        SecurityUtils.createDirectory(clientDataPath);
        System.out.println("[SEC]:Client Data Path = "+clientDataPath);
        try {
            Files.copy(Paths.get(clientKeyPath+File.separator+SecurityUtils.CLIENT_IDENTITY_KEY),
                        Paths.get(clientDataPath+File.separator+SecurityUtils.CLIENT_IDENTITY_KEY));
        
            Files.copy(Paths.get(clientKeyPath+File.separator+SecurityUtils.CLIENT_BASE_KEY),
                        Paths.get(clientDataPath+File.separator+SecurityUtils.CLIENT_BASE_KEY));
        } catch (IOException e) {
            System.out.println(e + "\n[SEC] INFO: Client Keys already exist Client Data Path for client ID "+clientID);
        }

        initializeClientKeysFromFiles(clientDataPath, clientSession);

        String sessionStorePath = clientDataPath + File.separator + SecurityUtils.SESSION_STORE_FILE;
        SessionRecord clientSessionRecord = null;

        try {
            byte[] sessionStoreBytes = SecurityUtils.readFromFile(sessionStorePath);
            clientSessionRecord   = new SessionRecord(sessionStoreBytes);
        } catch (IOException e) {
            System.out.println("[SEC]: Error Reading Session record from " + sessionStorePath +"\nCreating New Session Record!");
            clientSessionRecord = new SessionRecord();        
            initializeRatchet(clientSessionRecord.getSessionState(), clientSession);
        }

        clientSession.clientProtocolAddress  = new SignalProtocolAddress(clientID, clientID.hashCode());

        serverProtocolStore.storeSession(clientSession.clientProtocolAddress, clientSessionRecord);

        clientSession.cipherSession = new SessionCipher(serverProtocolStore, clientSession.clientProtocolAddress);
        updateSessionRecord(clientSession);

        clientMap.put(clientID, clientSession);

        return clientSession;
    }

    private void initializeClientKeysFromFiles(String path, ClientSession clientSession) throws InvalidKeyException, EncodingException
    {
        byte[] clientIdentityKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + SecurityUtils.CLIENT_IDENTITY_KEY);
        clientSession.IdentityKey = new IdentityKey(clientIdentityKey, 0);

        byte[] clientBaseKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + SecurityUtils.CLIENT_BASE_KEY);
        clientSession.BaseKey = Curve.decodePoint(clientBaseKey, 0);
        return;
    }

    private void initializeRatchet(SessionState serverSessionState, ClientSession clientSession) throws InvalidKeyException
    {
        BobSignalProtocolParameters parameters = BobSignalProtocolParameters.newBuilder()
                                                                                .setOurRatchetKey(ourRatchetKey)
                                                                                .setOurSignedPreKey(ourSignedPreKey)
                                                                                .setOurOneTimePreKey(Optional.<ECKeyPair>absent())
                                                                                .setOurIdentityKey(ourIdentityKeyPair)
                                                                                .setTheirIdentityKey(clientSession.IdentityKey)
                                                                                .setTheirBaseKey(clientSession.BaseKey)
                                                                                .create();
        RatchetingSession.initializeSession(serverSessionState, parameters);
    }
    
    private ClientSession getClientSession(String clientID)
    {
        if (clientMap.containsKey(clientID)) {
            return clientMap.get(clientID);
        } else {
            // TODO: Change to log
            System.out.println("[SEC]:Key[ " + clientID + " ] NOT found!");
        }
        return null;
    }

    private ClientSession getClientSessionFromFile(String clientKeyPath) throws InvalidClientSessionException
    {
        ClientSession client = null;
        try {
            String clientID = SecurityUtils.getClientID(clientKeyPath);
            client = getClientSession(clientKeyPath, clientID);
        } catch (InvalidKeyException | IDGenerationException | EncodingException e) {
            throw new InvalidClientSessionException("[SEC]:Error getting client session from file: "+e);
        }
        return client;
    }

    private void createSignature(byte[] fileContents, String signedFilePath) throws InvalidKeyException, FileNotFoundException, IOException
    {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = Base64.getUrlEncoder().encodeToString(signedData);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath)) {
            stream.write(encodedSignature.getBytes());
        }
    }

    private String getsharedSecret(ClientSession client) throws InvalidKeyException
    {
        byte[] agreement = Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    private String getsharedSecret(ECPublicKey clientIdentitKey) throws InvalidKeyException
    {
        byte[] agreement = Curve.calculateAgreement(clientIdentitKey, ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }
    
    private String getsharedSecret(String clientID) throws InvalidKeyException, InvalidClientIDException
    {
        /* get Client Session */
        ClientSession client = getClientSession(clientID);
        if ( client == null) {
            throw new InvalidClientIDException("Failed to get client [" + clientID + "]");
        }

        byte[] agreement = Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    /* Initialize or get previous server Security Instance */
    public static synchronized ServerSecurity getInstance(String serverRootPath) throws ServerIntializationException
    {
        if (singleServerInstance == null) {
            singleServerInstance = new ServerSecurity(serverRootPath);
        }

        return singleServerInstance;
    }

    public void decrypt(String bundlePath, String decryptedPath) throws IOException, InvalidClientSessionException
    {
        ClientSession client = getClientSessionFromFile(bundlePath);
        String payloadPath   = bundlePath + File.separator + SecurityUtils.PAYLOAD_DIR;
        String signPath      = bundlePath + File.separator + SecurityUtils.SIGNATURE_DIR;
        
        String bundleID      = getBundleIDFromFile(bundlePath);
        String decryptedFile = decryptedPath + File.separator + bundleID + SecurityUtils.DECRYPTED_FILE_EXT;
        
        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(decryptedPath));
        
        System.out.println(decryptedFile);
        int fileCount = new File(payloadPath).list().length;

        try {
                for (int i = 1; i <= fileCount; ++i) {
                    String payloadName      = SecurityUtils.PAYLOAD_FILENAME + String.valueOf(i);
                    String signatureFile    = signPath + File.separator + payloadName + SecurityUtils.SIGNATURE_FILENAME;

                    byte[] encryptedData = SecurityUtils.readFromFile(payloadPath + File.separator + payloadName);
                    byte[] serverDecryptedMessage  = client.cipherSession.decrypt(new SignalMessage (encryptedData));
                    updateSessionRecord(client);
                    try (FileOutputStream stream = new FileOutputStream(decryptedFile, true)) {
                        stream.write(serverDecryptedMessage);
                    }
                    System.out.printf("[SEC]:Decrypted Size = %d\n", serverDecryptedMessage.length);

                    if (SecurityUtils.verifySignature(serverDecryptedMessage, client.IdentityKey.getPublicKey(), signatureFile)) {
                        System.out.println("[SEC]:Verified Signature!");
                    } else {
                        // Failed to verify sign, delete bundle and return
                        System.out.println("[SEC]:Invalid Signature ["+ payloadName +"], Aborting bundle "+ bundleID);

                        try {
                            Files.deleteIfExists(Paths.get(decryptedFile));
                        }
                        catch (Exception e) {
                            System.out.printf("[SEC] Error: Failed to delete decrypted file [%s]", decryptedFile);
                            System.out.println(e);
                        }
                    }
                }
        } catch (Exception e) {
              System.out.println("[SEC]: Failed to Decrypt Client's Message\n" + e);
            e.printStackTrace();
        }
        return;
    }

    public String[] encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) throws InvalidClientSessionException, BundleIDCryptographyException, IOException, InvalidKeyException, EncodingException
    {
        /* get Client Session */
        ClientSession client = getClientSessionFromFile(clientRootPath+File.separator+clientID);
        if ( client == null) {
            throw new InvalidClientSessionException("Failed to get client [" + clientID + "]");
        }
        
        String bundlePath    = encPath + File.separator + bundleID + File.separator;
        String payloadPath   = bundlePath + File.separator + SecurityUtils.PAYLOAD_DIR;
        String signPath      = bundlePath + File.separator + SecurityUtils.SIGNATURE_DIR;
        File plainTextFile   = new File(toBeEncPath);
        List <String> returnPaths = new ArrayList<>();
        int len = 0;

        /* Create Directory if it does not exist */
        SecurityUtils.createDirectory(bundlePath);
        SecurityUtils.createDirectory(payloadPath);
        SecurityUtils.createDirectory(signPath);

        DataInputStream inputStream = new DataInputStream(new FileInputStream(plainTextFile));
        byte[] chunk = new byte[SecurityUtils.CHUNKSIZE];
        
        for (int i = 1; (len = inputStream.read(chunk)) != -1; i++)
        {
            String encBundlePath    = payloadPath + File.separator + SecurityUtils.PAYLOAD_FILENAME + String.valueOf(i);
            String signBundlePath   = signPath + File.separator + SecurityUtils.PAYLOAD_FILENAME + String.valueOf(i) + SecurityUtils.SIGNATURE_FILENAME;

            if (chunk.length != len) {
                chunk = Arrays.copyOf(chunk, len);
            }

            /* Create Signature with plaintext*/
            createSignature(chunk, signBundlePath);
            /* Encrypt File */
            CiphertextMessage cipherText = client.cipherSession.encrypt(chunk);
            updateSessionRecord(client);

            FileOutputStream stream = new FileOutputStream(encBundlePath);
            stream.write(cipherText.serialize());
            stream.close();
        }
        inputStream.close();
    
        /* Create Encryption Headers */
        String[] clientKeyPaths = createEncryptionHeader(encPath, bundleID, client);

        returnPaths.add(payloadPath);
        returnPaths.add(signPath);

        for (String clientKeyPath: clientKeyPaths) {
            returnPaths.add(clientKeyPath);
        }

        return returnPaths.toArray(new String[returnPaths.size()]);
    }

    public String[] createEncryptionHeader(String encPath, String bundleID, ClientSession client) throws IOException, EncodingException
    {
        String bundlePath   = encPath + File.separator + bundleID;

        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(bundlePath));
        /* Create Bundle ID File */
        createBundleIDFile(bundleID, client, bundlePath);
                
        /* Write Keys to Bundle directory */
        return writeKeysToFiles(bundlePath, false);
    }

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID, ClientSession client) throws BundleIDCryptographyException
    {
        String sharedSecret = null;
        try {
            sharedSecret = getsharedSecret(client);
        } catch (InvalidKeyException e) {
            throw new BundleIDCryptographyException("Failed to calculate shared secret for bundle ID: "+e);
        }

        try {
            return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
        } catch (AESAlgorithmException e) {
            throw new BundleIDCryptographyException("Failed to encrypt bundle ID: "+e);
        }
    }

    public String encryptBundleID(String bundleID, String clientID) throws BundleIDCryptographyException, InvalidClientIDException
    {
        String sharedSecret = null;
        try {
            sharedSecret = getsharedSecret(clientID);
        } catch (InvalidKeyException e) {
            throw new BundleIDCryptographyException("Failed to calculate shared secret for bundle ID: "+e);
        }

        try {
            return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
        } catch (AESAlgorithmException e) {
            throw new BundleIDCryptographyException("Failed to encrypt bundle ID: "+e);
        }
    }

    public void createBundleIDFile(String bundleID, ClientSession client, String bundlePath) throws IOException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        try (FileOutputStream stream = new FileOutputStream(bundleIDPath)) {
            stream.write(bundleID.getBytes());
        }
    }

    public String decryptBundleID(String encryptedBundleID, String clientID) throws BundleIDCryptographyException
    {
        String sharedSecret = null;
        byte[] bundleBytes  = null;

        try {
            sharedSecret = getsharedSecret(clientID);
        } catch (InvalidKeyException | InvalidClientIDException e) {
            throw new BundleIDCryptographyException("Error generating shared secret for bundle ID: "+e);
        }

        try {
            bundleBytes = SecurityUtils.dencryptAesCbcPkcs5(sharedSecret, encryptedBundleID);
        } catch (AESAlgorithmException e) {
            throw new BundleIDCryptographyException("Error in AES decryption for bundle ID: "+e);
        }

        return new String(bundleBytes, StandardCharsets.UTF_8);
    }

    public String getDecryptedBundleIDFromFile(String bundlePath, String clientID) throws IOException, BundleIDCryptographyException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        byte[] encryptedBundleID = SecurityUtils.readFromFile(bundleIDPath);
        
        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8), clientID);
    }

    public String getBundleIDFromFile(String bundlePath) throws FileNotFoundException, IOException
    {
        byte[] bundleIDBytes    = SecurityUtils.readFromFile(bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME);
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public String getServerRootPath() {
        return serverRootPath;
    }

    public String getClientRootPath() {
        return clientRootPath;
    }

    public int isNewerBundle(String bundlePath, String lastBundleID) throws IOException, BundleIDCryptographyException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        byte[] encryptedBundleID = SecurityUtils.readFromFile(bundleIDPath);
        String receivedBundleID, latestBundleID;

        try {
            byte[] clientIdentityKeyBytes = SecurityUtils.decodePublicKeyfromFile(bundlePath + File.separator + SecurityUtils.CLIENT_IDENTITY_KEY);
            IdentityKey clientIdentityKey = new IdentityKey(clientIdentityKeyBytes, 0);

            String sharedSecret = getsharedSecret(clientIdentityKey.getPublicKey());

            byte[] bundleIDbytes = SecurityUtils.dencryptAesCbcPkcs5(sharedSecret, new String(encryptedBundleID, StandardCharsets.UTF_8));
            receivedBundleID = new String(bundleIDbytes, StandardCharsets.UTF_8);
            bundleIDbytes = SecurityUtils.dencryptAesCbcPkcs5(sharedSecret, lastBundleID);
            latestBundleID = new String(bundleIDbytes, StandardCharsets.UTF_8);
        } catch (AESAlgorithmException | InvalidKeyException | EncodingException e) {
            throw new BundleIDCryptographyException("[SEC]: Failed to decrypt/decode BundleID:"+e);
        }

        return BundleIDGenerator.compareBundleIDs(receivedBundleID, latestBundleID, BundleIDGenerator.UPSTREAM);
    }
};
