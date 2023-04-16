package com.ddd.server.bundlesecurity;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
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
import com.ddd.server.bundlesecurity.SecurityExceptions.ClientSessionException;
import com.ddd.server.bundlesecurity.SecurityUtils.ClientSession;

public class ServerSecurity {

    private static final int ServerDeviceID = 0;
    private static ServerSecurity singleServerInstance = null;

    private SignalProtocolAddress           ourAddress;
    private IdentityKeyPair                 ourIdentityKeyPair;
    private ECKeyPair                       ourSignedPreKey;
    private Optional<ECKeyPair>             ourOneTimePreKey;
    private ECKeyPair                       ourRatchetKey;
    private HashMap<String, ClientSession>  clientMap;

    private ServerSecurity(String serverKeyPath) throws IOException, NoSuchAlgorithmException
    {
        clientMap  = new HashMap<>();

        if (new File(serverKeyPath).exists()) {
            try {
                loadKeysfromFiles(serverKeyPath);
                System.out.println("[Sec]: Using Existing Keys");
                return;
            } catch (InvalidKeyException e) {
                // TODO: Change to log
               System.out.println("[Sec]: Error Loading Keys from files, generating new keys instead");
            }
        }

        ECKeyPair identityKeyPair       = Curve.generateKeyPair();
        ourIdentityKeyPair              = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                                    identityKeyPair.getPrivateKey());
        ourSignedPreKey                 = Curve.generateKeyPair();
        ourRatchetKey                   = ourSignedPreKey;
        
        String name                     = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
        ourAddress                      = new SignalProtocolAddress(name, ServerDeviceID);
        ourOneTimePreKey                = Optional.<ECKeyPair>absent();

        writeKeysToFiles(serverKeyPath, true);
    }

    private void loadKeysfromFiles(String serverKeyPath) throws FileNotFoundException, IOException, InvalidKeyException
    {
        byte[] identityKey = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverIdentity.pvt");
        ourIdentityKeyPair = new IdentityKeyPair(identityKey);

        byte[] signedPreKeyPvt = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverSignedPreKey.pvt");
        byte[] signedPreKeyPub = SecurityUtils.decodePublicKeyfromFile(serverKeyPath + File.separator + "serverSignedPre.pub");

        ECPublicKey signedPreKeyPublicKey = Curve.decodePoint(signedPreKeyPub, 0);
        ECPrivateKey signedPreKeyPrivateKey = Curve.decodePrivatePoint(signedPreKeyPvt);

        ourSignedPreKey = new ECKeyPair(signedPreKeyPublicKey, signedPreKeyPrivateKey);

        byte[] ratchetKeyPvt = SecurityUtils.readFromFile(serverKeyPath + File.separator + "serverRatchetKey.pvt");
        byte[] ratchetKeyPub = SecurityUtils.decodePublicKeyfromFile(serverKeyPath + File.separator + "serverRatchet.pub");

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
    
    private String[] writeKeysToFiles(String path, boolean writePvt) throws IOException
    {
        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(path));
        
        String[] serverKeypaths = { path + File.separator + "serverIdentity.pub",
                                    path + File.separator + "serverSignedPre.pub",
                                    path + File.separator +  "serverRatchet.pub"};

        writePrivateKeys(path);
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), serverKeypaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourSignedPreKey.getPublicKey(), serverKeypaths[1]);
        SecurityUtils.createEncodedPublicKeyFile(ourRatchetKey.getPublicKey(), serverKeypaths[2]);
        return serverKeypaths;
    }

    private ClientSession inititalizeClientSession(String clientKeyPath, String clientID) throws IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        ClientSession clientSession = new ClientSession();

        initializeClientKeysFromFiles(clientKeyPath, clientSession);
        
        clientSession.serverSessionRecord = new SessionRecord();

        try {
            initializeRatchet(clientSession.serverSessionRecord.getSessionState(), clientSession);
        } catch (InvalidKeyException e) {
            System.out.println("Error Initializing Server Ratchet!\n" + e);
            e.printStackTrace();
        }

        SignalProtocolStore serverStore = SecurityUtils.createInMemorySignalProtocolStore();
        serverStore.storeSession(ourAddress, clientSession.serverSessionRecord);

        clientSession.cipherSession = new SessionCipher(serverStore, ourAddress);
        clientSession.clientID  = clientID;
        clientMap.put(clientID, clientSession);

        return clientSession;
    }

    private void initializeClientKeysFromFiles(String path, ClientSession clientSession) throws IOException, InvalidKeyException
    {
        byte[] clientIdentityKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "clientIdentity.pub");
        clientSession.IdentityKey = new IdentityKey(clientIdentityKey, 0);

        byte[] clientBaseKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "clientBase.pub");
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
    
    private ClientSession getClientSession(String clientID) throws IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        if (clientMap.containsKey(clientID)) {
            return clientMap.get(clientID);
        } else {
            // TODO: Change to log
            System.out.println("Key[ " + clientID + " ] NOT found!");
        }
        return null;
    }

    private ClientSession getClientSessionFromFile(String clientKeyPath) throws IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        String clientID = SecurityUtils.getClientID(clientKeyPath);
        
        ClientSession client = getClientSession(clientID);
        if ( client == null) {
            System.out.println("Creating new client session");
            client = inititalizeClientSession(clientKeyPath, clientID);
        }
        return client;
    }

    private void createSignature(byte[] fileContents, String signedFilePath) throws java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException
    {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = Base64.getUrlEncoder().encodeToString(signedData);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath)) {
            stream.write(encodedSignature.getBytes());
        } catch (Exception e) {
            System.out.println("Failed to write Signature to file:\n" + e);
        }
    }

    private String getsharedSecret(ClientSession client) throws ClientSessionException, InvalidKeyException
    {
        byte[] agreement = Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    private String getsharedSecret(String clientID) throws ClientSessionException, InvalidKeyException, NoSuchAlgorithmException, IOException
    {
        /* get Client Session */
        ClientSession client = getClientSession(clientID);
        if ( client == null) {
            // TODO: Change exception
            throw new InvalidKeyException("Failed to get client [" + clientID + "]");
        }

        byte[] agreement = Curve.calculateAgreement(client.IdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        return secretKey;
    }

    /* Initialize or get previous server Security Instance */
    public static synchronized ServerSecurity getInstance(String serverKeyPath) throws NoSuchAlgorithmException, IOException
    {
        if (singleServerInstance == null) {
            singleServerInstance = new ServerSecurity(serverKeyPath);
        }

        return singleServerInstance;
    }

    public void decrypt(String bundlePath, String decryptedPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException, java.security.InvalidKeyException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
        ClientSession client = getClientSessionFromFile(bundlePath);
        String payloadPath   = bundlePath + File.separator + SecurityUtils.PAYLOAD_DIR;
        String signPath      = bundlePath + File.separator + SecurityUtils.SIGNATURE_DIR;
        
        String bundleID      = getBundleIDFromFile(bundlePath, client.clientID);
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
                    try (FileOutputStream stream = new FileOutputStream(decryptedFile, true)) {
                        stream.write(serverDecryptedMessage);
                    }
                    System.out.printf("Decrypted Size = %d\n", serverDecryptedMessage.length);

                    if (SecurityUtils.verifySignature(serverDecryptedMessage, client.IdentityKey.getPublicKey(), signatureFile)) {
                        System.out.println("Verified Signature!");
                    } else {
                        // Failed to verify sign, delete bundle and return
                        System.out.println("Invalid Signature ["+ payloadName +"], Aborting bundle "+ bundleID);

                        try {
                            Files.deleteIfExists(Paths.get(decryptedFile));
                        }
                        catch (Exception e) {
                            System.out.printf("Error: Failed to delete decrypted file [%s]", decryptedFile);
                            System.out.println(e);
                        }
                    }
                }
        } catch (Exception e) {
            System.out.println("Failed to Decrypt Client's Message\n" + e);
            e.printStackTrace();
        }
        return;
    }

    public String[] encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
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

        /* get Client Session */
        ClientSession client = getClientSession(clientID);
        if ( client == null) {
            // TODO: Change exception
            throw new InvalidKeyException("Failed to get client [" + clientID + "]");
        }

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

    public String[] createEncryptionHeader(String encPath, String bundleID, ClientSession client) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException, ClientSessionException
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
    public String encryptBundleID(String bundleID, ClientSession client) throws InvalidKeyException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
        String sharedSecret = getsharedSecret(client);

        return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
    }

    public String encryptBundleID(String bundleID, String clientID) throws InvalidKeyException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ClientSessionException, IOException
    {
        String sharedSecret = getsharedSecret(clientID);

        return SecurityUtils.encryptAesCbcPkcs5(sharedSecret, bundleID);
    }

    public void createBundleIDFile(String bundleID, ClientSession client, String bundlePath) throws InvalidKeyException, NoSuchAlgorithmException, IOException, InvalidKeySpecException, NoSuchPaddingException, java.security.InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
        String encData = encryptBundleID(bundleID, client);

        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        try (FileOutputStream stream = new FileOutputStream(bundleIDPath)) {
            stream.write(encData.getBytes());
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public byte[] decryptBundleID(String encryptedBundleID, String clientID) throws InvalidKeyException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, ClientSessionException, IOException
    {
        String sharedSecret = getsharedSecret(clientID);
        return SecurityUtils.dencryptAesCbcPkcs5(sharedSecret, encryptedBundleID);
    }

    public String getBundleIDFromFile(String bundlePath, String clientID) throws IOException, InvalidKeyException, NoSuchAlgorithmException, java.security.InvalidKeyException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        byte[] encryptedBundleID = SecurityUtils.readFromFile(bundleIDPath);
        
        byte[] bundleBytes = decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8), clientID);

        return new String(bundleBytes, StandardCharsets.UTF_8);
    }
};
