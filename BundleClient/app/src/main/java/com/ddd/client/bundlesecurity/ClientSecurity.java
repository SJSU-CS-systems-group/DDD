package ddd;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.FileInputStream;
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
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;

import ddd.SecurityUtils;

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
    
    private void writeKeysToFiles(String path) throws IOException
    {
        SecurityUtils.createEncodedPublicKeyFile(ourIdentityKeyPair.getPublicKey().getPublicKey(), path + File.separator + "clientIdentity.pub");
        SecurityUtils.createEncodedPublicKeyFile(ourBaseKey.getPublicKey(), path + File.separator + "clientBase.pub");
        return;
    }

    private void InitializeServerKeysFromFiles(String path) throws IOException, InvalidKeyException
    {
        byte[] serverIdentityKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverIdentity.pub");
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);

        byte[] serverSignedPreKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverSignedPre.pub");
        theirSignedPreKey = Curve.decodePoint(serverSignedPreKey, 0);
        
        byte[] serverRatchetKey = SecurityUtils.decodePublicKeyfromFile(path + File.separator + "serverRatchet.pub");
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
        String encodedSignature = Base64.getUrlEncoder().encodeToString(signedData);

        try (FileOutputStream stream = new FileOutputStream(signedFilePath)) {
            stream.write(encodedSignature.getBytes());
        } catch (Exception e) {
            System.out.println("Failed to write Signature to file:\n" + e);
        }
    }

    /* Encrypts and creates a file for the BundleID */
    private void createBundleIDFile(String bundleID, String bundlePath) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, java.security.InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeySpecException
    {
        String encData = encryptBundleID(bundleID);

        String bundleIDPath = bundlePath + SecurityUtils.BUNDLEID_FILENAME;
        try (FileOutputStream stream = new FileOutputStream(bundleIDPath)) {
            stream.write(encData.getBytes());
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    /* Encrypts the given bundleID
     */
    public String encryptBundleID(String bundleID) throws InvalidKeyException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
    {
        byte[] agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());

        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);

        return SecurityUtils.encryptAesCbcPkcs5(secretKey, bundleID);
    }

    /* Add Headers (Identity, Base Key & Bundle ID) to Bundle Path */
    private void createEncryptionHeader(String encPath, String bundleID) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, InvalidKeySpecException, InvalidKeyException
    {
        String bundlePath   = encPath + File.separator + bundleID + File.separator;

        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(bundlePath));
        
        /* Write Keys to Bundle directory */
        writeKeysToFiles(bundlePath);

        /* Create Bundle ID File */
        createBundleIDFile(bundleID, bundlePath);
    }

    /* Initialize or get previous client Security Instance */
    public static synchronized ClientSecurity getInstance(int deviceID, String clientKeyPath, String serverKeyPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException
    {
        if (singleClientInstance == null) {
            singleClientInstance = new ClientSecurity(deviceID, clientKeyPath, serverKeyPath);
        }

        return singleClientInstance;
    }

    /* Encrypts File and creates signature for plain text */
    public String encrypt(String toBeEncPath, String encPath, String bundleID) throws IOException, java.security.InvalidKeyException, NoSuchAlgorithmException, SignatureException, InvalidKeySpecException, InvalidKeyException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
    {
        String bundlePath    = encPath + File.separator + bundleID + File.separator;
        String encBundlePath = bundlePath + SecurityUtils.PAYLOAD_FILENAME;
        String signPath      = bundlePath + SecurityUtils.SIGN_FILENAME;
        byte[] fileContents  = SecurityUtils.readFromFile(toBeEncPath);

        /* Create Directory if it does not exist */
        Files.createDirectories(Paths.get(bundlePath));

        /* Create Signature with plaintext*/
        createSignature(fileContents, signPath);

        /* Encrypt File */
        CiphertextMessage cipherText = cipherSession.encrypt(fileContents);
        FileOutputStream stream = new FileOutputStream(encBundlePath);
        stream.write(cipherText.serialize());
        stream.close();

        /* Create Encryption Headers */
        createEncryptionHeader(encPath, bundleID);
        return bundlePath;
    }
    
    // TODO: return decrypted file path 
    public void decrypt(String bundlePath, String decryptedPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException, java.security.InvalidKeyException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException
    {
        String payloadFile = bundlePath + File.separator + SecurityUtils.PAYLOAD_FILENAME;
        byte[] encryptedData = SecurityUtils.readFromFile(payloadFile);
        String bundleID      = getBundleIDFromFile(bundlePath);
        String decryptedFile = decryptedPath + File.separator + bundleID + SecurityUtils.DECRYPTED_FILE_EXT;
        String signatureFile = bundlePath + File.separator + SecurityUtils.SIGN_FILENAME;
        
        try {
            byte[] serverDecryptedMessage  = cipherSession.decrypt(new SignalMessage (encryptedData));
            try (FileOutputStream stream = new FileOutputStream(decryptedFile)) {
                stream.write(serverDecryptedMessage);
            }
            System.out.printf("Decrypted Size = %d\n", serverDecryptedMessage.length);
            
            if (SecurityUtils.verifySignature(serverDecryptedMessage, theirIdentityKey.getPublicKey(), signatureFile)) {
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

    public String decryptBundleID(String encryptedBundleID) throws InvalidKeyException, java.security.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException
    {
        byte[] agreement = Curve.calculateAgreement(theirIdentityKey.getPublicKey(), ourIdentityKeyPair.getPrivateKey());
        String secretKey = Base64.getUrlEncoder().encodeToString(agreement);
        byte[] bundleIDBytes = SecurityUtils.dencryptAesCbcPkcs5(secretKey, encryptedBundleID);
        return new String(bundleIDBytes, StandardCharsets.UTF_8);
    }

    public String getBundleIDFromFile(String bundlePath) throws IOException, InvalidKeyException, NoSuchAlgorithmException, java.security.InvalidKeyException, InvalidKeySpecException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException
    {
        String bundleIDPath = bundlePath + File.separator + SecurityUtils.BUNDLEID_FILENAME;
        byte[] encryptedBundleID = SecurityUtils.readFromFile(bundleIDPath);
        
        return decryptBundleID(new String(encryptedBundleID, StandardCharsets.UTF_8));
    }
}
