package com.ddd.client.bundlesecurity;

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
import java.util.List;

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
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;

import com.ddd.client.bundlesecurity.SecurityUtils;
import com.ddd.client.bundlesecurity.SecurityExceptions.AESAlgorithmException;
import com.ddd.client.bundlesecurity.SecurityExceptions.BundleDecryptionException;
import com.ddd.client.bundlesecurity.SecurityExceptions.EncodingException;
import com.ddd.client.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.client.bundlesecurity.SecurityExceptions.SignatureVerificationException;
import com.ddd.client.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;

public class ClientSecurity {

    private static ClientSecurity singleClientInstance = null;

    // Used to store in local signal protocol store so can be same across devices
    private SignalProtocolAddress ourAddress;
    // Identity Key Pairs public key is used as the CLient ID
    private IdentityKeyPair       ourIdentityKeyPair;
    private ECKeyPair             ourBaseKey;

    private IdentityKey           theirIdentityKey;
    private ECPublicKey           theirSignedPreKey;
    private Optional<ECPublicKey> theirOneTimePreKey;
    private ECPublicKey           theirRatchetKey;

    private SessionCipher         cipherSession;
    private SessionRecord         clientSessionRecord;

    private String                clientID;
    private String                clientKeyPath;

    private ClientSecurity(int deviceID, String clientKeyPath, String serverKeyPath) throws InvalidKeyException, IDGenerationException, EncodingException
    {
        // Create Client's Key pairs
        ECKeyPair identityKeyPair       = Curve.generateKeyPair();
        ourIdentityKeyPair              = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                        identityKeyPair.getPrivateKey());
        ourBaseKey                      = Curve.generateKeyPair();
        
        // Create Client ID
        clientID                        = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().getPublicKey().serialize());
        ourAddress                      = new SignalProtocolAddress(clientID, deviceID);
        theirOneTimePreKey              = Optional.<ECPublicKey>absent();
        this.clientKeyPath              = clientKeyPath;

        // Write generated keys to files
        writeKeysToFiles(clientKeyPath);
        // Read Server Keys from specified directory
        InitializeServerKeysFromFiles(serverKeyPath);
        // Create Client Cipher
        createCipher();
    }
    
    private String[] writeKeysToFiles(String path) throws EncodingException
    {
        /* Create Directory if it does not exist */
        SecurityUtils.createDirectory(path);
        String[] clientKeypaths = { path + File.separator + "clientIdentity.pub",
                                    path + File.separator + "clientBase.pub"};

        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), clientKeypaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourBaseKey.getPublicKey(), clientKeypaths[1]);
        return clientKeypaths;
    }

    private void InitializeServerKeysFromFiles(String path) throws InvalidKeyException
    {
        try {
            byte[] serverIdentityKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverIdentity.pub");
            theirIdentityKey = new IdentityKey(serverIdentityKey, 0);

            byte[] serverSignedPreKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverSignedPre.pub");
            theirSignedPreKey = Curve.decodePoint(serverSignedPreKey, 0);
            
            byte[] serverRatchetKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverRatchet.pub");
            theirRatchetKey = Curve.decodePoint(serverRatchetKey, 0);
        } catch (EncodingException e) {
            throw new InvalidKeyException("Error Decoding Public Key: "+e);
        }
        return;
    }

    private void initializeRatchet(SessionState clientSessionState) throws InvalidKeyException
    {
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

    private void createCipher()
    {
        clientSessionRecord   = new SessionRecord();

        try {
            initializeRatchet(clientSessionRecord.getSessionState());
        } catch (InvalidKeyException e) {
            System.out.println("Error Initializing Client Ratchet!\n" + e);
            e.printStackTrace();
        }

        SignalProtocolStore clientStore = SecurityUtils.createInMemorySignalProtocolStore();
        clientStore.storeSession(ourAddress, clientSessionRecord);

        cipherSession = new SessionCipher(clientStore, ourAddress);
    }

    private void createSignature(byte[] fileContents, String signedFilePath) throws FileNotFoundException, IOException, InvalidKeyException
    {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = Base64.encodeToString(signedData, Base64.URL_SAFE | Base64.NO_WRAP);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath)) {
            stream.write(encodedSignature.getBytes());
        }
    }

    /* Encrypts and creates a file for the BundleID */
    private void createBundleIDFile(String bundleID, String bundlePath) throws FileNotFoundException, IOException
    {
        String bundleIDPath = bundlePath + SecurityUtils.BUNDLEID_FILENAME;

        try (FileOutputStream stream = new FileOutputStream(bundleIDPath)) {
            stream.write(bundleID.getBytes());
        }
    }

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID) throws BundleIDCryptographyException
    {
        byte[] agreement = null;
        try {
            agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        } catch (InvalidKeyException e) {
            throw new BundleIDCryptographyException("Failed to calculate shared secret for bundle ID: "+e);
        }

        String secretKey = Base64.encodeToString(agreement, Base64.URL_SAFE | Base64.NO_WRAP);

        try {
            return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID);
        } catch (AESAlgorithmException e) {
            throw new BundleIDCryptographyException("Failed to encrypt bundle ID: "+e);
        }
    }

    /* Add Headers (Identity, Base Key & Bundle ID) to Bundle Path */
    private String[] createEncryptionHeader(String encPath, String bundleID) throws EncodingException, FileNotFoundException, IOException 
    {
        String bundlePath   = encPath + File.separator + bundleID;

        /* Create Directory if it does not exist */
        SecurityUtils.createDirectory(bundlePath);

        /* Create Bundle ID File */
        createBundleIDFile(bundleID, bundlePath);

        /* Write Keys to Bundle directory */
        return writeKeysToFiles(bundlePath);
    }

    /* Initialize or get previous client Security Instance */
    public static synchronized ClientSecurity getInstance(int deviceID, String clientKeyPath, String serverKeyPath) throws InvalidKeyException, IDGenerationException, EncodingException
    {
        if (singleClientInstance == null) {
            singleClientInstance = new ClientSecurity(deviceID, clientKeyPath, serverKeyPath);
        }

        return singleClientInstance;
    }

    /* Encrypts File and creates signature for plain text */
    public String[] encrypt(String toBeEncPath, String encPath, String bundleID) throws IOException, InvalidKeyException, EncodingException
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
            CiphertextMessage cipherText = cipherSession.encrypt(chunk);
            FileOutputStream stream = new FileOutputStream(encBundlePath);
            stream.write(cipherText.serialize());
            stream.close();
        }
        inputStream.close();
        
        /* Create Encryption Headers */
        String[] clientKeyPaths = createEncryptionHeader(encPath, bundleID);
        
        returnPaths.add(payloadPath);
        returnPaths.add(signPath);
        
        for (String clientKeyPath: clientKeyPaths) {
            returnPaths.add(clientKeyPath);
        }
        return returnPaths.toArray(new String[returnPaths.size()]);
    }
    
    public void decrypt(String bundlePath, String decryptedPath) throws FileNotFoundException, IOException, BundleDecryptionException, SignatureVerificationException
    {
        String payloadPath   = bundlePath + File.separator + SecurityUtils.PAYLOAD_DIR;
        String signPath      = bundlePath + File.separator + SecurityUtils.SIGNATURE_DIR;
        
        String bundleID      = getBundleIDFromFile(bundlePath);
        String decryptedFile = decryptedPath + File.separator + bundleID + SecurityUtils.DECRYPTED_FILE_EXT;
        
        /* Create Directory if it does not exist */
        SecurityUtils.createDirectory(decryptedPath);
        
        System.out.println(decryptedFile);
        int fileCount = new File(payloadPath).list().length;

        for (int i = 1; i <= fileCount; ++i) {
            String payloadName      = SecurityUtils.PAYLOAD_FILENAME + String.valueOf(i);
            String signatureFile    = signPath + File.separator + payloadName + SecurityUtils.SIGNATURE_FILENAME;

            byte[] encryptedData = SecurityUtils.readFromFile(payloadPath + File.separator + payloadName);
            byte[] serverDecryptedMessage = null;
            
            try {
                serverDecryptedMessage = cipherSession.decrypt(new SignalMessage (encryptedData));
            } catch (InvalidMessageException | DuplicateMessageException | LegacyMessageException
                    | NoSessionException e) {
                throw new BundleDecryptionException("Error Decrypting bundle: "+e);
            }

            try (FileOutputStream stream = new FileOutputStream(decryptedFile, true)) {
                stream.write(serverDecryptedMessage);
            }
            System.out.printf("Decrypted Size = %d\n", serverDecryptedMessage.length);

            if (SecurityUtils.verifySignature(serverDecryptedMessage, theirIdentityKey.getPublicKey(), signatureFile)) {
                System.out.println("Verified Signature!");
            } else {
                // Failed to verify sign, delete bundle and return
                System.out.println("Invalid Signature ["+ payloadName +"], Aborting bundle "+ bundleID);

                try {
                    new File(decryptedFile).delete();
                }
                catch (Exception e) {
                    System.out.printf("Error: Failed to delete decrypted file [%s]", decryptedFile);
                    System.out.println(e);
                }
            }
        }
        return;
    }

    
    public String decryptBundleID(String encryptedBundleID) throws BundleIDCryptographyException
    {
        byte[] agreement = null;
        byte[] bundleIDBytes = null;
        
        try {
            agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        } catch (InvalidKeyException e) {
            throw new BundleIDCryptographyException("Failed to calculate shared secret for bundle ID: "+e);
        }

        String secretKey = Base64.encodeToString(agreement, Base64.URL_SAFE | Base64.NO_WRAP);

        try {
            bundleIDBytes = SecurityUtils.dencryptAesCbcPkcs5(secretKey, encryptedBundleID);
        } catch (AESAlgorithmException e) {
            throw new BundleIDCryptographyException("Failed to decrypt bundle ID: "+e);
        }
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public String getDecryptedBundleIDFromFile(String bundlePath) throws IOException, BundleIDCryptographyException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        byte[] encryptedBundleID = SecurityUtils.readFromFile(bundleIDPath);
        
        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8));
    }

    public String getClientID()
    {
        return this.clientID;
    }

    public String getBundleIDFromFile(String bundlePath) throws FileNotFoundException, IOException
    {
        byte[] bundleIDBytes    = SecurityUtils.readFromFile(bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME);
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }
    
    public String getClientKeyPath() {
        return clientKeyPath;
    }

}