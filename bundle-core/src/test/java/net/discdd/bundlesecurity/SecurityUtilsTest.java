package net.discdd.bundlesecurity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class SecurityUtilsTest {
    private SessionCipher aliceCipher;
    private SessionCipher bobCipher;
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
    @BeforeEach
    public void setUp() throws InvalidKeyException {
        SessionRecord aliceSessionRecord = new SessionRecord();
        SessionRecord bobSessionRecord = new SessionRecord();

        initializeSessionsV3(aliceSessionRecord.getSessionState(), bobSessionRecord.getSessionState());

        SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
        SignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();

        aliceStore.storeSession(new SignalProtocolAddress("+14159999999", 1), aliceSessionRecord);
        bobStore.storeSession(new SignalProtocolAddress("+14158888888", 1), bobSessionRecord);

        aliceCipher = new SessionCipher(aliceStore, new SignalProtocolAddress("+14159999999", 1));
        bobCipher = new SessionCipher(bobStore, new SignalProtocolAddress("+14158888888", 1));


    }

    /**
     *
     * Encrypts with StreamedProcess
     * Decrypts with Array
     */
    @Test
    public void test1ArrayEncryptArrayDecrypt() throws Exception {
        String message = "Hello, World!";
        Method encryptMethod = SessionCipher.class.getDeclaredMethod("encrypt", byte[].class);
        encryptMethod.setAccessible(true); // Make it accessible
        SignalMessage signalm = (SignalMessage) encryptMethod.invoke(aliceCipher, message.getBytes());
        encryptMethod.setAccessible(false);
        byte[] plaintext = bobCipher.decrypt(signalm);
        String decrypted = new String(plaintext, StandardCharsets.UTF_8);
        Assertions.assertEquals(message, decrypted);
    }


    /**
     *
     * Encrypts with StreamedProcess
     * Decrypts with Array
     */
    @Test
    public void test2StreamingEncryptStreamingDecrypt() throws Exception {
        String message = "Hello, World!";
        ByteArrayOutputStream cipherText = new ByteArrayOutputStream();
        aliceCipher.encrypt(new ByteArrayInputStream(message.getBytes()), cipherText);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        SessionCipher.readSize = 31;
        bobCipher.decrypt(new ByteArrayInputStream(cipherText.toByteArray()), outputStream);
        String decrypted = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        Assertions.assertEquals(message, decrypted);
    }
    @Test
    public void test3StreamingEncryptStreamingDecrypt() throws Exception {
        String message = "Hello, World!";
        ByteArrayOutputStream cipherText = new ByteArrayOutputStream();
        aliceCipher.encrypt(new ByteArrayInputStream(message.getBytes()), cipherText);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bobCipher.decrypt(new ByteArrayInputStream(cipherText.toByteArray()), outputStream);
        String decrypted = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        Assertions.assertEquals(message, decrypted);
    }
}
