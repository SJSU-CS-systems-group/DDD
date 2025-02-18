package net.discdd.bundlesecurity;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestInMemorySignalProtocolStore implements SignalProtocolStore {
    private Map<SignalProtocolAddress, SessionRecord> sessions = new HashMap<>();

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        sessions.put(address, record);
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        return sessions.get(address);
    }

    @Override
    public List<Integer> getSubDeviceSessions(String s) {
        return null;
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
    public void deleteAllSessions(String s) {

    }

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        return null;
    }

    @Override
    public int getLocalRegistrationId() {
        return 0;
    }

    @Override
    public void saveIdentity(String s, IdentityKey identityKey) {

    }

    @Override
    public boolean isTrustedIdentity(String s, IdentityKey identityKey) {
        return false;
    }

    @Override
    public PreKeyRecord loadPreKey(int i) throws InvalidKeyIdException {
        return null;
    }

    @Override
    public void storePreKey(int i, PreKeyRecord preKeyRecord) {

    }

    @Override
    public boolean containsPreKey(int i) {
        return false;
    }

    @Override
    public void removePreKey(int i) {

    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int i) throws InvalidKeyIdException {
        return null;
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        return null;
    }

    @Override
    public void storeSignedPreKey(int i, SignedPreKeyRecord signedPreKeyRecord) {

    }

    @Override
    public boolean containsSignedPreKey(int i) {
        return false;
    }

    @Override
    public void removeSignedPreKey(int i) {

    }
}

