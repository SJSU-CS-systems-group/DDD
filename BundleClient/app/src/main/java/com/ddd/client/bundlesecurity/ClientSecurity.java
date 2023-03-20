package com.ddd.client.bundlesecurity;

import android.util.Base64;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
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
import com.ddd.datastore.filestore.FileStoreHelper;

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

    private SessionCipher           cipherSession;
    private SessionRecord           clientSessionRecord;

    // TODO: Handle restart, create ratchet session with existing keys
    private ClientSecurity(int deviceID, String clientKeyPath, String serverKeyPath) throws IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        // Create Client's Key pairs
        ECKeyPair identityKeyPair       = Curve.generateKeyPair();
        ourIdentityKeyPair              = new IdentityKeyPair(new IdentityKey(identityKeyPair.getPublicKey()),
                                                        identityKeyPair.getPrivateKey());
        ourBaseKey                      = Curve.generateKeyPair();
        
        // Create Client ID
        String ClientID                 = SecurityUtils.generateID(ourIdentityKeyPair.getPublicKey().serialize());
        ourAddress                      = new SignalProtocolAddress(ClientID, deviceID);
        theirOneTimePreKey              = Optional.<ECPublicKey>absent();
        
        // Write generated keys to files
        writeKeysToFiles(clientKeyPath);
        // Read Server Keys from specified directory
        InitializeServerKeysFromFiles(serverKeyPath);
        // Create Client Cipher
        createCipher();
    }
    
    private String[] writeKeysToFiles(String path) throws IOException
    {
        String[] clientPublicKeyPaths = {path + "clientIdentity.pub", path + "clientBase.pub"};
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), clientPublicKeyPaths[0]);
        SecurityUtils.createEncodedPublicKeyFile(ourBaseKey.getPublicKey(), clientPublicKeyPaths[1]);
        return clientPublicKeyPaths;
    }

    private void InitializeServerKeysFromFiles(String path) throws IOException, InvalidKeyException
    {
        byte[] serverIdentityKey = SecurityUtils.decodePublicKeyfromFile(path + "serverIdentity.pub");
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);

        byte[] serverSignedPreKey = SecurityUtils.decodePublicKeyfromFile(path + "serverSignedPre.pub");
        theirSignedPreKey = Curve.decodePoint(serverSignedPreKey, 0);
        
        byte[] serverRatchetKey = SecurityUtils.decodePublicKeyfromFile(path + "serverRatchet.pub");
        theirRatchetKey = Curve.decodePoint(serverRatchetKey, 0);
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

    private void createSignature(byte[] fileContents, String signedFilePath) throws java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException
    {
        byte[] signedData = Curve.calculateSignature(ourIdentityKeyPair.getPrivateKey(), fileContents);
        String encodedSignature = android.util.Base64.encodeToString(signedData, Base64.DEFAULT);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath)) {
            stream.write(encodedSignature.getBytes());
        } catch (Exception e) {
            System.out.println("Failed to write Signature to file:\n" + e);
        }
    }
        
    public static synchronized ClientSecurity getInstance(int deviceID, String clientKeyPath, String serverKeyPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException
    {
        /* Create Directory if it does not exist */

//        Files.createDirectories(Paths.get(clientKeyPath));
        File file = new File(clientKeyPath);
        if (!file.exists()) {
            file.mkdirs();
        }
        if (singleClientInstance == null) {
            singleClientInstance = new ClientSecurity(deviceID, clientKeyPath, serverKeyPath);
        }

        return singleClientInstance;
    }

    /* Encrypts File and creates signature for plain text */
    public String[] encrypt(String toBeEncPath, String encPath, String bundleID) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException
    {
        String bundlePath    = encPath + bundleID + "\\";
        String encBundlePath = bundlePath + SecurityUtils.PAYLOAD_FILENAME;
        String signPath      = bundlePath + SecurityUtils.SIGN_FILENAME;
//        byte[] fileContents  =  Files.readAllBytes(Paths.get(toBeEncPath));
        byte[] fileContents  = new byte[0];
        try {
            fileContents = FileStoreHelper.getStringFromFile(toBeEncPath).getBytes();
        } catch (Exception e) {
            e.printStackTrace();
        }
        /* Create Directory if it does not exist */
//        Files.createDirectories(Paths.get(bundlePath));
        File file = new File(bundlePath);
        if (!file.exists()) {
            file.mkdirs();
        }
        /* Create Signature with plaintext*/
        createSignature(fileContents, signPath);

        /* Encrypt File */
        CiphertextMessage cipherText = cipherSession.encrypt(fileContents);
        FileOutputStream stream = new FileOutputStream(encBundlePath);
        stream.write(cipherText.serialize());
        stream.close();

        return new String[] {encBundlePath, signPath};
    }

    /* Add Headers (Identity, Base Key & Bundle ID) to Bundle Path */
    public String[] createEncryptionHeader(String encPath, String bundleID) throws IOException
    {
        String   bundlePath   = encPath + bundleID + "\\";
        String   bundleIDPath = bundlePath + SecurityUtils.BUNDLEID_FILENAME;

        /* Create Directory if it does not exist */
//        Files.createDirectories(Paths.get(bundlePath));
        File file = new File(bundlePath);
        if (!file.exists()) {
            file.mkdirs();
        }
        /* Create Bundle ID File */
        FileOutputStream stream = new FileOutputStream(bundleIDPath);
        stream.write(bundleID.getBytes());
        stream.close();

        /* Write Keys to Bundle directory */
        return writeKeysToFiles(bundlePath);
    }

    public void decrypt(String bundlePath, String decryptedPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException
    {
//        byte[] encryptedData = Files.readAllBytes(Paths.get(bundlePath + SecurityUtils.PAYLOAD_FILENAME));
        byte[] encryptedData  = new byte[0];
        try {
            encryptedData = FileStoreHelper.getStringFromFile(bundlePath + SecurityUtils.PAYLOAD_FILENAME).getBytes();
        } catch (Exception e) {
            e.printStackTrace();
        }

        byte[] bundleIDData  = new byte[0];
        try {
            bundleIDData = FileStoreHelper.getStringFromFile(bundlePath+SecurityUtils.BUNDLEID_FILENAME).getBytes();
        } catch (Exception e) {
            e.printStackTrace();
        }
        String bundleID      = new String (bundleIDData);

        String decryptedFile = decryptedPath + bundleID + SecurityUtils.DECRYPTED_FILE_EXT;
        
        try {
            byte[] serverDecryptedMessage  = cipherSession.decrypt(new SignalMessage (encryptedData));
            try (FileOutputStream stream = new FileOutputStream(decryptedFile)) {
                stream.write(serverDecryptedMessage);
            }
            System.out.printf("Decrypted Size = %d\n", serverDecryptedMessage.length);
            if (SecurityUtils.verifySignature(serverDecryptedMessage, theirIdentityKey.getPublicKey(), bundlePath + SecurityUtils.SIGN_FILENAME)) {
                System.out.println("Verified Signature!");
            } else {
                // Failed to verify sign, delete bundle and return
                System.out.println("Invalid Signature, Aborting bundle "+ bundleID);

                try {
                    File file = new File(decryptedFile);
                    file.delete();
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
}
