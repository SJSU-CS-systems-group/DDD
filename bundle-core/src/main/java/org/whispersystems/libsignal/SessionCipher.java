/**
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.libsignal;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.ChainKey;
import org.whispersystems.libsignal.ratchet.MessageKeys;
import org.whispersystems.libsignal.ratchet.RootKey;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.IdentityKeyStore;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignedPreKeyStore;
import org.whispersystems.libsignal.util.ByteUtil;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.whispersystems.libsignal.state.SessionState.UnacknowledgedPreKeyMessageItems;

/**
 * The main entry point for Signal Protocol encrypt/decrypt operations.
 *
 * Once a session has been established with {@link SessionBuilder},
 * this class can be used for all encrypt/decrypt operations within
 * that session.
 *
 * @author Moxie Marlinspike
 */
public class SessionCipher {

    public static final Object SESSION_LOCK = new Object();

    private final SessionStore          sessionStore;
    private final SessionBuilder        sessionBuilder;
    private final PreKeyStore           preKeyStore;
    private final SignalProtocolAddress remoteAddress;

    /**
     * Construct a SessionCipher for encrypt/decrypt operations on a session.
     * In order to use SessionCipher, a session must have already been created
     * and stored using {@link SessionBuilder}.
     *
     * @param  sessionStore The {@link SessionStore} that contains a session for this recipient.
     * @param  remoteAddress  The remote address that messages will be encrypted to or decrypted from.
     */
    public SessionCipher(SessionStore sessionStore, PreKeyStore preKeyStore,
                         SignedPreKeyStore signedPreKeyStore, IdentityKeyStore identityKeyStore,
                         SignalProtocolAddress remoteAddress)
    {
        this.sessionStore   = sessionStore;
        this.preKeyStore    = preKeyStore;
        this.remoteAddress  = remoteAddress;
        this.sessionBuilder = new SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore,
                                                 identityKeyStore, remoteAddress);
    }

    public SessionCipher(SignalProtocolStore store, SignalProtocolAddress remoteAddress) {
        this(store, store, store, store, remoteAddress);
    }
    /**
     * THIS METHOD ASSUMES IDENTITY IS TRUSTED
     * USE THE METHOD WITH STREAMING, THIS ENCRYPTION IS FOR TESTING
     *
     * @param  paddedMessage The plaintext message bytes, optionally padded to a constant multiple.
     * @return A ciphertext message encrypted to the recipient+device tuple.
     */
    private CiphertextMessage encrypt(byte[] paddedMessage) throws UntrustedIdentityException {
        synchronized (SESSION_LOCK) {
            SessionRecord sessionRecord   = sessionStore.loadSession(remoteAddress);
            SessionState  sessionState    = sessionRecord.getSessionState();
            ChainKey      chainKey        = sessionState.getSenderChainKey();
            MessageKeys   messageKeys     = chainKey.getMessageKeys();
            ECPublicKey   senderEphemeral = sessionState.getSenderRatchetKey();
            int           previousCounter = sessionState.getPreviousCounter();
            int           sessionVersion  = sessionState.getSessionVersion();

            byte[]            ciphertextBody    = getCiphertext(messageKeys, paddedMessage);
            CiphertextMessage ciphertextMessage = new SignalMessage(sessionVersion, messageKeys.getMacKey(),
                                                                    senderEphemeral, chainKey.getIndex(),
                                                                    previousCounter, ciphertextBody,
                                                                    sessionState.getLocalIdentityKey(),
                                                                    sessionState.getRemoteIdentityKey());

            if (sessionState.hasUnacknowledgedPreKeyMessage()) {
                UnacknowledgedPreKeyMessageItems items = sessionState.getUnacknowledgedPreKeyMessageItems();
                int localRegistrationId = sessionState.getLocalRegistrationId();

                ciphertextMessage = new PreKeySignalMessage(sessionVersion, localRegistrationId, items.getPreKeyId(),
                                                            items.getSignedPreKeyId(), items.getBaseKey(),
                                                            sessionState.getLocalIdentityKey(),
                                                            (SignalMessage) ciphertextMessage);
            }

            sessionState.setSenderChainKey(chainKey.getNextChainKey());


            //identityKeyStore.saveIdentity(remoteAddress, sessionState.getRemoteIdentityKey());
            //sessionStore.storeSession(remoteAddress, sessionRecord);
            return ciphertextMessage;
        }
    }

    /**
     * Encrypt a message.
     *
     * @return A ciphertext message encrypted to the recipient+device tuple.
     */
    public CiphertextMessage encrypt(InputStream inputStream, OutputStream outputStream) throws IOException {
        synchronized (SESSION_LOCK) {
            SessionRecord sessionRecord   = sessionStore.loadSession(remoteAddress);
            SessionState  sessionState    = sessionRecord.getSessionState();
            ChainKey      chainKey        = sessionState.getSenderChainKey();
            MessageKeys   messageKeys     = chainKey.getMessageKeys();
            ECPublicKey   senderEphemeral = sessionState.getSenderRatchetKey();
            int           previousCounter = sessionState.getPreviousCounter();
            int           sessionVersion  = sessionState.getSessionVersion();

            getCiphertext(sessionVersion, messageKeys, inputStream, outputStream);
            CiphertextMessage ciphertextMessage = new SignalMessage(sessionVersion, messageKeys.getMacKey(),
                                                                    senderEphemeral, chainKey.getIndex(),
                                                                    previousCounter,new byte[0],
                                                                    sessionState.getLocalIdentityKey(),
                                                                    sessionState.getRemoteIdentityKey());

            if (sessionState.hasUnacknowledgedPreKeyMessage()) {
                UnacknowledgedPreKeyMessageItems items = sessionState.getUnacknowledgedPreKeyMessageItems();
                int localRegistrationId = sessionState.getLocalRegistrationId();

                ciphertextMessage = new PreKeySignalMessage(sessionVersion, localRegistrationId, items.getPreKeyId(),
                                                            items.getSignedPreKeyId(), items.getBaseKey(),
                                                            sessionState.getLocalIdentityKey(),
                                                            (SignalMessage) ciphertextMessage);
            }

            sessionState.setSenderChainKey(chainKey.getNextChainKey());
            sessionStore.storeSession(remoteAddress, sessionRecord);
            return ciphertextMessage;
        }
    }


    public byte[] decrypt(PreKeySignalMessage ciphertext)
            throws DuplicateMessageException, LegacyMessageException, InvalidMessageException,
            InvalidKeyIdException, InvalidKeyException, UntrustedIdentityException
    {
        return decrypt(ciphertext, new NullDecryptionCallback());
    }


    public byte[] decrypt(PreKeySignalMessage ciphertext, DecryptionCallback callback)
            throws DuplicateMessageException, LegacyMessageException, InvalidMessageException,
            InvalidKeyIdException, InvalidKeyException, UntrustedIdentityException
    {
        synchronized (SESSION_LOCK) {
            SessionRecord     sessionRecord    = sessionStore.loadSession(remoteAddress);
            Optional<Integer> unsignedPreKeyId = sessionBuilder.process(sessionRecord, ciphertext);
            byte[]            plaintext        = decrypt(sessionRecord, ciphertext.getWhisperMessage());

            //callback.handlePlaintext(plaintext);

            sessionStore.storeSession(remoteAddress, sessionRecord);

            if (unsignedPreKeyId.isPresent()) {
                preKeyStore.removePreKey(unsignedPreKeyId.get());
            }

            return plaintext;
        }
    }


    public byte[] decrypt(SignalMessage ciphertext)
            throws InvalidMessageException, DuplicateMessageException, LegacyMessageException,
            NoSessionException
    {
        return decrypt(ciphertext, new NullDecryptionCallback());
    }


    public byte[] decrypt(SignalMessage ciphertext, DecryptionCallback callback)
            throws InvalidMessageException, DuplicateMessageException, LegacyMessageException,
            NoSessionException
    {
        synchronized (SESSION_LOCK) {

            if (!sessionStore.containsSession(remoteAddress)) {
                throw new NoSessionException("No session for: " + remoteAddress);
            }

            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            byte[]        plaintext     = decrypt(sessionRecord, ciphertext);

            //callback.handlePlaintext(plaintext);

            sessionStore.storeSession(remoteAddress, sessionRecord);

            return plaintext;
        }
    }

    private byte[] decrypt(SessionRecord sessionRecord, SignalMessage ciphertext)
            throws DuplicateMessageException, LegacyMessageException, InvalidMessageException
    {
        synchronized (SESSION_LOCK) {
            Iterator<SessionState> previousStates = sessionRecord.getPreviousSessionStates().iterator();
            List<Exception>        exceptions     = new LinkedList<>();

            try {
                SessionState sessionState = new SessionState(sessionRecord.getSessionState());
                byte[]       plaintext    = decrypt(sessionState, ciphertext);

                sessionRecord.setState(sessionState);
                return plaintext;
            } catch (InvalidMessageException e) {
                exceptions.add(e);
            }

            while (previousStates.hasNext()) {
                try {
                    SessionState promotedState = new SessionState(previousStates.next());
                    byte[]       plaintext     = decrypt(promotedState, ciphertext);

                    previousStates.remove();
                    sessionRecord.promoteState(promotedState);

                    return plaintext;
                } catch (InvalidMessageException e) {
                    exceptions.add(e);
                }
            }

            throw new InvalidMessageException("No valid sessions.", exceptions);
        }
    }

    private byte[] decrypt(SessionState sessionState, SignalMessage ciphertextMessage)
            throws InvalidMessageException, DuplicateMessageException, LegacyMessageException
    {
        if (!sessionState.hasSenderChain()) {
            throw new InvalidMessageException("Uninitialized session!");
        }

        if (ciphertextMessage.getMessageVersion() != sessionState.getSessionVersion()) {
            throw new InvalidMessageException(String.format("Message version %d, but session version %d",
                                                            ciphertextMessage.getMessageVersion(),
                                                            sessionState.getSessionVersion()));
        }

        int            messageVersion    = ciphertextMessage.getMessageVersion();
        ECPublicKey    theirEphemeral    = ciphertextMessage.getSenderRatchetKey();
        int            counter           = ciphertextMessage.getCounter();
        ChainKey       chainKey          = getOrCreateChainKey(sessionState, theirEphemeral);
        MessageKeys    messageKeys       = getOrCreateMessageKeys(sessionState, theirEphemeral,
                                                                  chainKey, counter);

        ciphertextMessage.verifyMac(messageVersion,
                                    sessionState.getRemoteIdentityKey(),
                                    sessionState.getLocalIdentityKey(),
                                    messageKeys.getMacKey());

        byte[] plaintext = getPlaintext(messageVersion, messageKeys, ciphertextMessage.getBody());

        sessionState.clearUnacknowledgedPreKeyMessage();

        return plaintext;
    }
    public void decrypt(SignalMessage ciphertext, ByteArrayInputStream inputStream, ByteArrayOutputStream outputStream)
            throws InvalidMessageException, DuplicateMessageException, LegacyMessageException, NoSessionException {
        synchronized (SESSION_LOCK) {
            if (!sessionStore.containsSession(remoteAddress)) {
                throw new NoSessionException("No session for: " + remoteAddress);
            }

            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            Iterator<SessionState> previousStates = sessionRecord.getPreviousSessionStates().iterator();
            List<Exception> exceptions = new LinkedList<>();

            while (true) {
                try {
                    SessionState sessionState = previousStates.hasNext()
                            ? new SessionState(previousStates.next())
                            : new SessionState(sessionRecord.getSessionState());

                    if (!sessionState.hasSenderChain()) {
                        throw new InvalidMessageException("Uninitialized session!");
                    }

                    if (ciphertext.getMessageVersion() != sessionState.getSessionVersion()) {
                        throw new InvalidMessageException(String.format("Message version %d, but session version %d",
                                                                        ciphertext.getMessageVersion(), sessionState.getSessionVersion()));
                    }

                    ECPublicKey theirEphemeral = ciphertext.getSenderRatchetKey();
                    int counter = ciphertext.getCounter();
                    ChainKey chainKey = getOrCreateChainKey(sessionState, theirEphemeral);
                    MessageKeys messageKeys = getOrCreateMessageKeys(sessionState, theirEphemeral, chainKey, counter);

                    ciphertext.verifyMac(ciphertext.getMessageVersion(),
                                         sessionState.getRemoteIdentityKey(),
                                         sessionState.getLocalIdentityKey(),
                                         messageKeys.getMacKey());

                    getPlaintext(ciphertext.getMessageVersion(), messageKeys, inputStream, outputStream);
                    sessionState.clearUnacknowledgedPreKeyMessage();

                    if (previousStates.hasNext()) {
                        previousStates.remove();
                        sessionRecord.promoteState(sessionState);
                    } else {
                        sessionRecord.setState(sessionState);
                    }

                    sessionStore.storeSession(remoteAddress, sessionRecord);
                    return;
                } catch (InvalidMessageException e) {
                    exceptions.add(e);
                    if (!previousStates.hasNext()) {
                        throw new InvalidMessageException("No valid sessions.", exceptions);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public int getRemoteRegistrationId() {
        synchronized (SESSION_LOCK) {
            SessionRecord record = sessionStore.loadSession(remoteAddress);
            return record.getSessionState().getRemoteRegistrationId();
        }
    }

    public int getSessionVersion() {
        synchronized (SESSION_LOCK) {
            if (!sessionStore.containsSession(remoteAddress)) {
                throw new IllegalStateException(String.format("No session for (%s)!", remoteAddress));
            }

            SessionRecord record = sessionStore.loadSession(remoteAddress);
            return record.getSessionState().getSessionVersion();
        }
    }

    private ChainKey getOrCreateChainKey(SessionState sessionState, ECPublicKey theirEphemeral)
            throws InvalidMessageException
    {
        try {
            if (sessionState.hasReceiverChain(theirEphemeral)) {
                return sessionState.getReceiverChainKey(theirEphemeral);
            } else {
                RootKey                 rootKey         = sessionState.getRootKey();
                ECKeyPair               ourEphemeral    = sessionState.getSenderRatchetKeyPair();
                Pair<RootKey, ChainKey> receiverChain   = rootKey.createChain(theirEphemeral, ourEphemeral);
                ECKeyPair               ourNewEphemeral = Curve.generateKeyPair();
                Pair<RootKey, ChainKey> senderChain     = receiverChain.first().createChain(theirEphemeral, ourNewEphemeral);

                sessionState.setRootKey(senderChain.first());
                sessionState.addReceiverChain(theirEphemeral, receiverChain.second());
                sessionState.setPreviousCounter(Math.max(sessionState.getSenderChainKey().getIndex()-1, 0));
                sessionState.setSenderChain(ourNewEphemeral, senderChain.second());

                return receiverChain.second();
            }
        } catch (InvalidKeyException e) {
            throw new InvalidMessageException(e);
        }
    }

    private MessageKeys getOrCreateMessageKeys(SessionState sessionState,
                                               ECPublicKey theirEphemeral,
                                               ChainKey chainKey, int counter)
            throws InvalidMessageException, DuplicateMessageException
    {
        if (chainKey.getIndex() > counter) {
            if (sessionState.hasMessageKeys(theirEphemeral, counter)) {
                return sessionState.removeMessageKeys(theirEphemeral, counter);
            } else {
                throw new DuplicateMessageException("Received message with old counter: " +
                                                            chainKey.getIndex() + " , " + counter);
            }
        }

        if (counter - chainKey.getIndex() > 2000) {
            throw new InvalidMessageException("Over 2000 messages into the future!");
        }

        while (chainKey.getIndex() < counter) {
            MessageKeys messageKeys = chainKey.getMessageKeys();
            sessionState.setMessageKeys(theirEphemeral, messageKeys);
            chainKey = chainKey.getNextChainKey();
        }

        sessionState.setReceiverChainKey(theirEphemeral, chainKey.getNextChainKey());
        return chainKey.getMessageKeys();
    }
    private byte[] getCiphertext(MessageKeys messageKeys, byte[] plaintext) {
        try {
            Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getIv());
            return cipher.doFinal(plaintext);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new AssertionError(e);
        }
    }


    private void getCiphertext(int version, MessageKeys messageKeys, InputStream plaintext, OutputStream cipherText) throws IOException {
        try {
            Cipher cipher;

            if (version >= 3) {
                cipher = getCipher(Cipher.ENCRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getIv());
            } else {
                cipher = getCipher(Cipher.ENCRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getCounter());
            }
            var bytes = new byte[64 * 1024];
            int rc;
            while ((rc = plaintext.read(bytes)) > 0) {
                cipherText.write(cipher.update(bytes, 0, rc));
            }
            cipherText.write(cipher.doFinal());

        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new AssertionError(e);
        }
    }

    private byte[] getPlaintext(int version, MessageKeys messageKeys, byte[] cipherText)
            throws InvalidMessageException
    {
        try {
            Cipher cipher;

            if (version >= 3) {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getIv());
            } else {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getCounter());
            }

            return cipher.doFinal(cipherText);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new InvalidMessageException(e);
        }
    }

    /**
     * Streamed decryption
     * @param version
     * @param messageKeys
     * @param byteArrayInputStream
     * @param byteArrayOutputStream
     * @throws InvalidMessageException
     */
    private void getPlaintext(int version, MessageKeys messageKeys, ByteArrayInputStream byteArrayInputStream, ByteArrayOutputStream byteArrayOutputStream )
            throws InvalidMessageException, IOException
    {
        try {
            Cipher cipher;

            if (version >= 3) {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getIv());
            } else {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getCounter());
            }
            var bytes = new byte[64 * 1024];
            int rc;
            while ((rc = byteArrayInputStream.read(bytes)) > 0) {
                byteArrayOutputStream.write(cipher.update(bytes, 0, rc));
            }
            byteArrayOutputStream.write(cipher.doFinal());

        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new InvalidMessageException(e);
        }
    }

    private Cipher getCipher(int mode, SecretKeySpec key, int counter)  {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");

            byte[] ivBytes = new byte[16];
            ByteUtil.intToByteArray(ivBytes, 0, counter);

            IvParameterSpec iv = new IvParameterSpec(ivBytes);
            cipher.init(mode, key, iv);

            return cipher;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | java.security.InvalidKeyException |
                 InvalidAlgorithmParameterException e)
        {
            throw new AssertionError(e);
        }
    }

    private Cipher getCipher(int mode, SecretKeySpec key, IvParameterSpec iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, key, iv);
            return cipher;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | java.security.InvalidKeyException |
                 InvalidAlgorithmParameterException e)
        {
            throw new AssertionError(e);
        }
    }



    private static class NullDecryptionCallback implements DecryptionCallback {
        @Override
        public void handlePlaintext(byte[] plaintext) {}
    }
}