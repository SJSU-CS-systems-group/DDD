package net.discdd.bundlesecurity;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.util.*;

/**
 * An in-memory implementation of SignalProtocolStore for testing purposes.
 */
public class TestInMemorySignalProtocolStore implements SignalProtocolStore {

    private final Map<SignalProtocolAddress, SessionRecord> sessions = new HashMap<>();
    private final Map<Integer, PreKeyRecord> preKeys = new HashMap<>();
    private final Map<Integer, SignedPreKeyRecord> signedPreKeys = new HashMap<>();
    private final IdentityKeyPair identityKeyPair;
    private final int registrationId;

    public TestInMemorySignalProtocolStore() throws InvalidKeyException {
        this.identityKeyPair = generateIdentityKeyPair();
        this.registrationId = new Random().nextInt(16380) + 1; // Signal uses 1-16380 as valid IDs

        // Generate and store PreKeys
        int preKeyId = 1;
        int signedPreKeyId = 1;

        PreKeyRecord preKey = new PreKeyRecord(preKeyId, Curve.generateKeyPair());
        SignedPreKeyRecord signedPreKey = new SignedPreKeyRecord(
                signedPreKeyId,
                System.currentTimeMillis(),
                Curve.generateKeyPair(),
                Curve.calculateSignature(
                        identityKeyPair.getPrivateKey(),
                        Curve.generateKeyPair().getPublicKey().serialize()
                )
        );

        storePreKey(preKeyId, preKey);
        storeSignedPreKey(signedPreKeyId, signedPreKey);
    }


    private IdentityKeyPair generateIdentityKeyPair() {
        var keyPair = Curve.generateKeyPair();
        return new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        sessions.put(address, record);
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        return sessions.getOrDefault(address, new SessionRecord());
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        return new ArrayList<>();
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        return sessions.containsKey(address);
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        sessions.remove(address);
    }

    @Override
    public void deleteAllSessions(String name) {
        sessions.clear();
    }

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        return identityKeyPair;
    }

    @Override
    public int getLocalRegistrationId() {
        return registrationId;
    }

    @Override
    public void saveIdentity(SignalProtocolAddress signalProtocolAddress, IdentityKey identityKey) {
        //Not Needed, evrything should occur in memory
    }

    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress signalProtocolAddress, IdentityKey identityKey) {
        return true; // Assuming trust during testing
    }

    @Override
    public PreKeyRecord loadPreKey(int keyId) throws InvalidKeyIdException {
        if (!preKeys.containsKey(keyId)) {
            throw new InvalidKeyIdException("PreKey not found for ID: " + keyId);
        }
        return preKeys.get(keyId);
    }

    @Override
    public void storePreKey(int keyId, PreKeyRecord preKeyRecord) {
        preKeys.put(keyId, preKeyRecord);
    }

    @Override
    public boolean containsPreKey(int keyId) {
        return preKeys.containsKey(keyId);
    }

    @Override
    public void removePreKey(int keyId) {
        preKeys.remove(keyId);
    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int keyId) throws InvalidKeyIdException {
        if (!signedPreKeys.containsKey(keyId)) {
            throw new InvalidKeyIdException("SignedPreKey not found for ID: " + keyId);
        }
        return signedPreKeys.get(keyId);
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        return new ArrayList<>(signedPreKeys.values());
    }

    @Override
    public void storeSignedPreKey(int keyId, SignedPreKeyRecord signedPreKeyRecord) {
        signedPreKeys.put(keyId, signedPreKeyRecord);
    }

    @Override
    public boolean containsSignedPreKey(int keyId) {
        return signedPreKeys.containsKey(keyId);
    }

    @Override
    public void removeSignedPreKey(int keyId) {
        signedPreKeys.remove(keyId);
    }
    public PreKeyBundle getPreKeyBundle() throws InvalidKeyIdException {
        int preKeyId = preKeys.keySet().iterator().next();
        int signedPreKeyId = signedPreKeys.keySet().iterator().next();

        return new PreKeyBundle(
                getLocalRegistrationId(),
                1, // Device ID
                preKeyId,
                loadPreKey(preKeyId).getKeyPair().getPublicKey(),
                signedPreKeyId,
                loadSignedPreKey(signedPreKeyId).getKeyPair().getPublicKey(),
                loadSignedPreKey(signedPreKeyId).getSignature(),
                getIdentityKeyPair().getPublicKey()
        );
    }

}
