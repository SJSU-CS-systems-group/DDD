package net.discdd.bundlesecurity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringBufferInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


import org.whispersystems.libsignal.protocol.SignalMessage;
import java.lang.reflect.Field;


public class SecurityUtilsTest {
    private void initializeSessionsV3(SessionState aliceSessionState, SessionState bobSessionState) throws InvalidKeyException {
        ECKeyPair aliceIdentityKeyPair = Curve.generateKeyPair();
        IdentityKeyPair aliceIdentityKey = new IdentityKeyPair(new IdentityKey(aliceIdentityKeyPair.getPublicKey()),
                                                               aliceIdentityKeyPair.getPrivateKey());
        ECKeyPair aliceBaseKey = Curve.generateKeyPair();
        ECKeyPair aliceEphemeralKey = Curve.generateKeyPair();

        ECKeyPair alicePreKey = aliceBaseKey;

        ECKeyPair bobIdentityKeyPair = Curve.generateKeyPair();
        IdentityKeyPair bobIdentityKey = new IdentityKeyPair(new IdentityKey(bobIdentityKeyPair.getPublicKey()),
                                                             bobIdentityKeyPair.getPrivateKey());
        ECKeyPair bobBaseKey = Curve.generateKeyPair();
        ECKeyPair bobEphemeralKey = bobBaseKey;

        ECKeyPair bobPreKey = Curve.generateKeyPair();

        AliceSignalProtocolParameters aliceParameters =
                AliceSignalProtocolParameters.newBuilder().setOurBaseKey(aliceBaseKey)
                        .setOurIdentityKey(aliceIdentityKey).setTheirOneTimePreKey(Optional.<ECPublicKey>absent())
                        .setTheirRatchetKey(bobEphemeralKey.getPublicKey())
                        .setTheirSignedPreKey(bobBaseKey.getPublicKey())
                        .setTheirIdentityKey(bobIdentityKey.getPublicKey()).create();

        BobSignalProtocolParameters bobParameters =
                BobSignalProtocolParameters.newBuilder().setOurRatchetKey(bobEphemeralKey)
                        .setOurSignedPreKey(bobBaseKey).setOurOneTimePreKey(Optional.<ECKeyPair>absent())
                        .setOurIdentityKey(bobIdentityKey).setTheirIdentityKey(aliceIdentityKey.getPublicKey())
                        .setTheirBaseKey(aliceBaseKey.getPublicKey()).create();

        RatchetingSession.initializeSession(aliceSessionState, aliceParameters);
        RatchetingSession.initializeSession(bobSessionState, bobParameters);
    }

    @Test
    public void testStreamingEncryptDecrypt() throws Exception {

        SessionRecord aliceSessionRecord = new SessionRecord();
        SessionRecord bobSessionRecord = new SessionRecord();

        initializeSessionsV3(aliceSessionRecord.getSessionState(), bobSessionRecord.getSessionState());

        SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
        SignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();

        aliceStore.storeSession(new SignalProtocolAddress("+14159999999", 1), aliceSessionRecord);
        bobStore.storeSession(new SignalProtocolAddress("+14158888888", 1), bobSessionRecord);

        SessionCipher aliceCipher = new SessionCipher(aliceStore, new SignalProtocolAddress("+14159999999", 1));
        SessionCipher bobCipher = new SessionCipher(bobStore, new SignalProtocolAddress("+14158888888", 1));

        String message = "Hello, World!";
        ByteArrayOutputStream cipherText = new ByteArrayOutputStream();

        SignalMessage signalMessage = (SignalMessage) bobCipher.encrypt(new ByteArrayInputStream(message.getBytes()), cipherText);

        byte[] plainText = aliceCipher.decrypt(signalMessage);
        String decryptedMessage = new String(plainText, StandardCharsets.UTF_8);

        System.out.println("Encrypted Message: " + Arrays.toString(cipherText.toByteArray()));
        System.out.println("Decrypted Message: " + decryptedMessage);
        Assertions.assertEquals(message, decryptedMessage);

    }
}