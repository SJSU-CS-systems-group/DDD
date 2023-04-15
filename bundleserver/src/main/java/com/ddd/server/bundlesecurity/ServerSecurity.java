package com.ddd.server.bundlesecurity;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

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
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;

import com.ddd.server.bundlesecurity.SecurityUtils;
import com.ddd.server.bundlesecurity.BundleID;
import com.ddd.server.bundlesecurity.SecurityUtils.ClientSession;
import com.ddd.server.bundlesecurity.SecurityExceptions.ClientSessionException;

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

        writeKeysToFiles(serverKeyPath);
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
    
    private void writeKeysToFiles(String path) throws IOException
    {
        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(path));
        
        writePrivateKeys(path);
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), path + File.separator +  "serverIdentity.pub");
        SecurityUtils.createEncodedPublicKeyFile(ourSignedPreKey.getPublicKey(), path + File.separator +  "serverSignedPre.pub");
        SecurityUtils.createEncodedPublicKeyFile(ourRatchetKey.getPublicKey(), path + File.separator +  "serverRatchet.pub");
        return;
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
        byte[] encryptedData = Files.readAllBytes(Paths.get(bundlePath + File.separator + SecurityUtils.PAYLOAD_FILENAME));
        String bundleID      = getBundleIDFromFile(bundlePath, client.clientID);
        String decryptedFile = decryptedPath + File.separator + bundleID + SecurityUtils.DECRYPTED_FILE_EXT;
        String signatureFile = bundlePath + File.separator + SecurityUtils.SIGN_FILENAME;
        
        String clientID = BundleID.getClientIDFromBundleID(bundleID, BundleID.DOWNSTREAM);
        System.out.println("ClientID from Bundle ID: "+clientID);
        System.out.println("Generated Client ID: "+client.clientID);
        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(decryptedPath));
        
        System.out.println(decryptedFile);
        try {
            byte[] serverDecryptedMessage  = client.cipherSession.decrypt(new SignalMessage (encryptedData));
            try (FileOutputStream stream = new FileOutputStream(decryptedFile)) {
                stream.write(serverDecryptedMessage);
            }
            System.out.printf("Decrypted Size = %d\n", serverDecryptedMessage.length);

            if (SecurityUtils.verifySignature(serverDecryptedMessage, client.IdentityKey.getPublicKey(), signatureFile)) {
                System.out.println("Verified Signature!");
            } else {
                // Failed to verify sign, delete bundle and return
                System.out.println("Invalid Signature, Aborting bundle "+ bundleID);

                try {
                    Files.deleteIfExists(Paths.get(decryptedFile));
                }
                catch (Exception e) {
                    System.out.printf("Error: Failed to delete decrypted file [%s]", decryptedFile);
                    System.out.println(e);
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to Decrypt Client's Message\n" + e);
            e.printStackTrace();
        }
        return;
    }

    public void decryptChunks(String bundlePath, String decryptedPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException, java.security.InvalidKeyException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
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

    public void encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ClientSessionException
    {
        String bundlePath    = encPath + File.separator + bundleID;
        String encBundlePath = bundlePath + File.separator + SecurityUtils.PAYLOAD_FILENAME;
        String signPath      = bundlePath + File.separator + SecurityUtils.SIGN_FILENAME;
        byte[] fileContents  =  Files.readAllBytes(Paths.get(toBeEncPath));

        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(bundlePath));

        /* Create Signature with plaintext*/
        createSignature(fileContents, signPath);

        /* get Client Session */
        ClientSession client = getClientSession(clientID);
        if ( client != null) {
            /* Encrypt File */
            CiphertextMessage cipherText = client.cipherSession.encrypt(fileContents);
            FileOutputStream stream = new FileOutputStream(encBundlePath);
            stream.write(cipherText.serialize());
            stream.close();
            /* Create Encryption Header */
            createEncryptionHeader(encPath, bundleID, client);
        } else {
            // TODO: Change exception
            throw new InvalidKeyException("Failed to get client [" + clientID + "]");
        }
    }

    public void createEncryptionHeader(String encPath, String bundleID, ClientSession client) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException, ClientSessionException
    {
        String bundlePath   = encPath + File.separator + bundleID;

        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(bundlePath));
        
        /* Write Keys to Bundle directory */
        writeKeysToFiles(bundlePath);

        /* Create Bundle ID File */
        createBundleIDFile(bundleID, client, bundlePath);
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
