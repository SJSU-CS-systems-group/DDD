/**
 * Copyright (C) 2013 Open Whisper Systems
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.libsignal;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.whispersystems.libsignal.DecryptionCallback;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;

import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.DjbECPublicKey;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.protocol.SignalProtos;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static java.lang.Math.max;
import static java.lang.Math.min;
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

    private final SessionStore sessionStore;
    private final SessionBuilder sessionBuilder;
    private final PreKeyStore preKeyStore;
    private final SignalProtocolAddress remoteAddress;

    /**
     * Construct a SessionCipher for encrypt/decrypt operations on a session.
     * In order to use SessionCipher, a session must have already been created
     * and stored using {@link SessionBuilder}.
     *
     * @param  sessionStore The {@link SessionStore} that contains a session for this recipient.
     * @param  remoteAddress  The remote address that messages will be encrypted to or decrypted from.
     */
    public SessionCipher(SessionStore sessionStore, PreKeyStore preKeyStore, SignedPreKeyStore signedPreKeyStore,
                         IdentityKeyStore identityKeyStore, SignalProtocolAddress remoteAddress) {
        this.sessionStore = sessionStore;
        this.preKeyStore = preKeyStore;
        this.remoteAddress = remoteAddress;
        this.sessionBuilder =
                new SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, remoteAddress);
    }

    public SessionCipher(SignalProtocolStore store, SignalProtocolAddress remoteAddress) {
        this(store, store, store, store, remoteAddress);
    }

    /**
     * Original Method
     * THIS METHOD ASSUMES IDENTITY IS TRUSTED
     * USE THE METHOD WITH STREAMING, THIS ENCRYPTION IS FOR TESTING
     *
     * @param  paddedMessage The plaintext message bytes, optionally padded to a constant multiple.
     * @return A ciphertext message encrypted to the recipient+device tuple.
     */
    private CiphertextMessage encrypt(byte[] paddedMessage) throws UntrustedIdentityException {
        synchronized (SESSION_LOCK) {
            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            SessionState sessionState = sessionRecord.getSessionState();
            ChainKey chainKey = sessionState.getSenderChainKey();
            MessageKeys messageKeys = chainKey.getMessageKeys();
            ECPublicKey senderEphemeral = sessionState.getSenderRatchetKey();
            int previousCounter = sessionState.getPreviousCounter();
            int sessionVersion = sessionState.getSessionVersion();

            byte[] ciphertextBody = getCiphertext(messageKeys, paddedMessage);
            CiphertextMessage ciphertextMessage =
                    new SignalMessage(sessionVersion, messageKeys.getMacKey(), senderEphemeral, chainKey.getIndex(),
                                      previousCounter, ciphertextBody, sessionState.getLocalIdentityKey(),
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
     * Encrypt a message.a
     *
     * @return A ciphertext message encrypted to the recipient+device tuple.
     */
    public void encrypt(InputStream inputStream, OutputStream outputStream) throws IOException {
        synchronized (SESSION_LOCK) {
            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            SessionState sessionState = sessionRecord.getSessionState();
            ChainKey chainKey = sessionState.getSenderChainKey();
            MessageKeys messageKeys = chainKey.getMessageKeys();
            ECPublicKey senderEphemeral = sessionState.getSenderRatchetKey();
            int previousCounter = sessionState.getPreviousCounter();
            int sessionVersion = sessionState.getSessionVersion();

            //First Byte into Output
            outputStream.write(sessionVersion);

            //Next Byte Shows Length of PK
            outputStream.write(senderEphemeral.serialize().length);
            outputStream.write(senderEphemeral.serialize());

            //Counter
            outputStream.write(ByteUtil.intToByteArray(messageKeys.getCounter()));

            //PrevCounter
            outputStream.write(ByteUtil.intToByteArray(previousCounter));

            //Cipher Text Message
            getCiphertext(sessionVersion, messageKeys, inputStream, outputStream);

            //outputStream.write(new byte[16]);
            //Mac
            outputStream.write(messageKeys.getMacKey().getEncoded());

            // Update session state
            sessionState.setSenderChainKey(chainKey.getNextChainKey());
            sessionStore.storeSession(remoteAddress, sessionRecord);
        }
    }

    public void decrypt(InputStream inputStream, OutputStream outputStream) throws InvalidMessageException, DuplicateMessageException, NoSessionException, IOException, InvalidKeyException {
        synchronized (SESSION_LOCK) {
            if (!sessionStore.containsSession(remoteAddress)) {
                throw new NoSessionException("No session for: " + remoteAddress);
            }

            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            Iterator<SessionState> previousStates = sessionRecord.getPreviousSessionStates().iterator();
            List<Exception> exceptions = new LinkedList<>();

            // Load session state
            SessionState sessionState = previousStates.hasNext() ? new SessionState(previousStates.next())
                    : new SessionState(sessionRecord.getSessionState());
            if (!sessionState.hasSenderChain()) {
                throw new InvalidMessageException("Uninitialized session!");
            }

            byte[] versionArr = new byte[1];
            if(inputStream.read(versionArr) == -1){
                throw new InvalidMessageException("No Version Found");
            }
            int version = versionArr[0];
            if(version < 3){
                throw new InvalidMessageException("Version is less than 3");
            }
            byte[] ratchetKeyInfo = new byte[1];
            if(inputStream.read(ratchetKeyInfo) == -1){
                throw new InvalidMessageException("No Ratchet KeyInfo");
            }
            byte[] ratchetKey = new byte[ratchetKeyInfo[0]];
            if(inputStream.read(ratchetKey) != ratchetKeyInfo[0]){
                throw new InvalidMessageException("Not enough Ratchet Key Bytes Found:"
                                                          + ratchetKey.length +" Expected:" + ratchetKeyInfo[0]);
            }

            //Counter
            byte[] counterArr = new byte[4];
            inputStream.read(counterArr);
            int counter = ByteUtil.byteArrayToInt(counterArr);

            //PrevCounter
            byte[] prevCounterArr = new byte[4];
            inputStream.read(prevCounterArr);
            int prevCounter = ByteUtil.byteArrayToInt(counterArr);

            ECPublicKey theirEphemeral = Curve.decodePoint(ratchetKey,0);

            ChainKey chainKey = getOrCreateChainKey(sessionState, theirEphemeral);
            MessageKeys messageKeys = getOrCreateMessageKeys(sessionState, theirEphemeral, chainKey, counter);



            // Verify the Mac
            byte[] mac = getPlaintext(version, messageKeys ,inputStream, outputStream);
            if(messageKeys.getMacKey().equals(mac)){
                throw new InvalidMessageException("Bad Mac!");
            }
            // Clear any unacknowledged PreKey messages
            sessionState.clearUnacknowledgedPreKeyMessage();

            // Promote or store the session state
            if (previousStates.hasNext()) {
                previousStates.remove();
                sessionRecord.promoteState(sessionState);
            } else {
                sessionRecord.setState(sessionState);
            }
            sessionStore.storeSession(remoteAddress, sessionRecord);
        }
    }

    public byte[] decrypt(PreKeySignalMessage ciphertext) throws DuplicateMessageException,
            InvalidMessageException, InvalidKeyIdException, InvalidKeyException, UntrustedIdentityException {
        return decrypt(ciphertext, new NullDecryptionCallback());
    }

    public byte[] decrypt(PreKeySignalMessage ciphertext, DecryptionCallback callback) throws DuplicateMessageException, InvalidMessageException, InvalidKeyIdException, InvalidKeyException, UntrustedIdentityException {
        synchronized (SESSION_LOCK) {
            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            Optional<Integer> unsignedPreKeyId = sessionBuilder.process(sessionRecord, ciphertext);
            byte[] plaintext = decrypt(sessionRecord, ciphertext.getWhisperMessage());

            //callback.handlePlaintext(plaintext);

            sessionStore.storeSession(remoteAddress, sessionRecord);

            if (unsignedPreKeyId.isPresent()) {
                preKeyStore.removePreKey(unsignedPreKeyId.get());
            }

            return plaintext;
        }
    }

    public byte[] decrypt(SignalMessage ciphertext) throws InvalidMessageException, DuplicateMessageException,
            NoSessionException {
        return decrypt(ciphertext, new NullDecryptionCallback());
    }

    public byte[] decrypt(SignalMessage ciphertext, DecryptionCallback callback) throws InvalidMessageException,
            DuplicateMessageException, NoSessionException {
        synchronized (SESSION_LOCK) {

            if (!sessionStore.containsSession(remoteAddress)) {
                throw new NoSessionException("No session for: " + remoteAddress);
            }

            SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
            byte[] plaintext = decrypt(sessionRecord, ciphertext);
            callback.handlePlaintext(plaintext);

            sessionStore.storeSession(remoteAddress, sessionRecord);

            return plaintext;
        }
    }

    private byte[] decrypt(SessionRecord sessionRecord, SignalMessage ciphertext) throws DuplicateMessageException,
            InvalidMessageException {
        synchronized (SESSION_LOCK) {
            Iterator<SessionState> previousStates = sessionRecord.getPreviousSessionStates().iterator();
            List<Exception> exceptions = new LinkedList<>();

            try {
                SessionState sessionState = new SessionState(sessionRecord.getSessionState());
                byte[] plaintext = decrypt(sessionState, ciphertext);

                sessionRecord.setState(sessionState);
                return plaintext;
            } catch (InvalidMessageException e) {
                exceptions.add(e);
            }

            while (previousStates.hasNext()) {
                try {
                    SessionState promotedState = new SessionState(previousStates.next());
                    byte[] plaintext = decrypt(promotedState, ciphertext);

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

    private byte[] decrypt(SessionState sessionState, SignalMessage ciphertextMessage) throws InvalidMessageException
            , DuplicateMessageException {
        if (!sessionState.hasSenderChain()) {
            throw new InvalidMessageException("Uninitialized session!");
        }

        if (ciphertextMessage.getMessageVersion() != sessionState.getSessionVersion()) {
            throw new InvalidMessageException(
                    String.format("Message version %d, but session version %d", ciphertextMessage.getMessageVersion(),
                                  sessionState.getSessionVersion()));
        }

        int messageVersion = ciphertextMessage.getMessageVersion();
        ECPublicKey theirEphemeral = ciphertextMessage.getSenderRatchetKey();
        int counter = ciphertextMessage.getCounter();
        ChainKey chainKey = getOrCreateChainKey(sessionState, theirEphemeral);
        MessageKeys messageKeys = getOrCreateMessageKeys(sessionState, theirEphemeral, chainKey, counter);

        ciphertextMessage.verifyMac(messageVersion, sessionState.getRemoteIdentityKey(),
                                    sessionState.getLocalIdentityKey(), messageKeys.getMacKey());

        byte[] plaintext = getPlaintext(messageVersion, messageKeys, ciphertextMessage.getBody());

        sessionState.clearUnacknowledgedPreKeyMessage();

        return plaintext;
    }


    private ChainKey getOrCreateChainKey(SessionState sessionState, ECPublicKey theirEphemeral) throws InvalidMessageException {
        try {
            if (sessionState.hasReceiverChain(theirEphemeral)) {
                return sessionState.getReceiverChainKey(theirEphemeral);
            } else {
                RootKey rootKey = sessionState.getRootKey();
                ECKeyPair ourEphemeral = sessionState.getSenderRatchetKeyPair();
                Pair<RootKey, ChainKey> receiverChain = rootKey.createChain(theirEphemeral, ourEphemeral);
                ECKeyPair ourNewEphemeral = Curve.generateKeyPair();
                Pair<RootKey, ChainKey> senderChain =
                        receiverChain.first().createChain(theirEphemeral, ourNewEphemeral);

                sessionState.setRootKey(senderChain.first());
                sessionState.addReceiverChain(theirEphemeral, receiverChain.second());
                sessionState.setPreviousCounter(max(sessionState.getSenderChainKey().getIndex() - 1, 0));
                sessionState.setSenderChain(ourNewEphemeral, senderChain.second());

                return receiverChain.second();
            }
        } catch (InvalidKeyException e) {
            throw new InvalidMessageException(e);
        }
    }

    private MessageKeys getOrCreateMessageKeys(SessionState sessionState, ECPublicKey theirEphemeral,
                                               ChainKey chainKey, int counter) throws InvalidMessageException,
            DuplicateMessageException {
        if (chainKey.getIndex() > counter) {
            if (sessionState.hasMessageKeys(theirEphemeral, counter)) {
                return sessionState.removeMessageKeys(theirEphemeral, counter);
            } else {
                throw new DuplicateMessageException(
                        "Received message with old counter: " + chainKey.getIndex() + " , " + counter);
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

    private byte[] getPlaintext(int version, MessageKeys messageKeys, byte[] cipherText) throws InvalidMessageException {
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

     * @throws InvalidMessageException
     * returns MAC
     */
    private byte[] getPlaintext(int version, MessageKeys messageKeys, InputStream inputStream,
                                OutputStream outputStream) throws InvalidMessageException, IOException {
        try {
            Cipher cipher;
            if (version >= 3) {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getIv());
            } else {
                cipher = getCipher(Cipher.DECRYPT_MODE, messageKeys.getCipherKey(), messageKeys.getCounter());
            }
            CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream,cipher);


            byte[] buffer = new byte[8192];  // Improved buffer size for better performance
            byte[] trailingBuffer = new byte[32]; // To store the last 32 bytes

            int TRAILING_SIZE = 32;
            int trailingCount = 0;

            int readCount;
            while ((readCount = inputStream.read(buffer)) > 0) {
                // If We Read in more than 32 bytes
                // Write whatever is in trailing, if anything
                // Write the full buffer to cipherOutputStream, excluding the last 32 bytes
                // Copy The Last 32 into the Trailing Buffer
                if (readCount > TRAILING_SIZE) {
                    cipherOutputStream.write(trailingBuffer, 0 , trailingCount);
                    cipherOutputStream.write(buffer, 0, readCount - TRAILING_SIZE);
                    System.arraycopy(buffer, 0,trailingBuffer,0, TRAILING_SIZE);
                }

                // If we read less than 32
                // Divide Into two cases Trailing = or != to 32
                // = 32
                // readCount leaves from Trailing buffer
                // Shift Over in Trailing
                // Shift From Buffer to Trailing
                // != 32
                // Will the readcount overflow our trailing bufffer
                // if
                //  readOut the necessary bytes
                //  shiftOver
                //  else
                //  write the bytes to the Trailing
                if (readCount < TRAILING_SIZE) {
                    if(trailingCount == 32){
                        cipherOutputStream.write(buffer,0, readCount);
                        System.arraycopy(trailingBuffer, 32 - readCount, trailingBuffer, 0, 32-readCount);
                        System.arraycopy(buffer, 0, trailingBuffer,32 - readCount , 32-readCount);
                    }else{
                        if(readCount + trailingCount > 32){
                            int leavingTrailing = 32 - (readCount + trailingCount);
                            cipherOutputStream.write(trailingBuffer, 0, leavingTrailing);
                            System.arraycopy(trailingBuffer, leavingTrailing, trailingBuffer, 0, TRAILING_SIZE- leavingTrailing);
                            System.arraycopy(buffer, 0, trailingBuffer, TRAILING_SIZE- leavingTrailing, leavingTrailing);
                            trailingCount = 32;
                        }else{
                            System.arraycopy(buffer, 0, trailingBuffer, trailingCount, readCount);
                            trailingCount += readCount;
                        }
                    }


                }
            }

            cipherOutputStream.close();
            return trailingBuffer;
        }catch(Exception e){
            throw new IOException(e.getMessage());
        }
    }


    private Cipher getCipher(int mode, SecretKeySpec key, int counter) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");

            byte[] ivBytes = new byte[16];
            ByteUtil.intToByteArray(ivBytes, 0, counter);

            IvParameterSpec iv = new IvParameterSpec(ivBytes);
            cipher.init(mode, key, iv);

            return cipher;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | java.security.InvalidKeyException |
                 InvalidAlgorithmParameterException e) {
            throw new AssertionError(e);
        }
    }

    private Cipher getCipher(int mode, SecretKeySpec key, IvParameterSpec iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, key, iv);
            return cipher;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | java.security.InvalidKeyException |
                 InvalidAlgorithmParameterException e) {
            throw new AssertionError(e);
        }
    }

    private static class NullDecryptionCallback implements DecryptionCallback {
        @Override
        public void handlePlaintext(byte[] plaintext) {}
    }
}