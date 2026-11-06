package net.discdd.bundlesecurity;

import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.tls.DDDTLSUtil;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;

public class SecurityUtilsTest {
    private SessionCipher aliceCipher;
    private SessionCipher bobCipher;

    private void initializeSessionsV3(SessionState aliceSessionState, SessionState bobSessionState) throws
            InvalidKeyException {
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

        AliceSignalProtocolParameters aliceParameters = AliceSignalProtocolParameters.newBuilder()
                .setOurBaseKey(aliceBaseKey)
                .setOurIdentityKey(aliceIdentityKey)
                .setTheirOneTimePreKey(Optional.<ECPublicKey>absent())
                .setTheirRatchetKey(bobEphemeralKey.getPublicKey())
                .setTheirSignedPreKey(bobBaseKey.getPublicKey())
                .setTheirIdentityKey(bobIdentityKey.getPublicKey())
                .create();

        BobSignalProtocolParameters bobParameters = BobSignalProtocolParameters.newBuilder()
                .setOurRatchetKey(bobEphemeralKey)
                .setOurSignedPreKey(bobBaseKey)
                .setOurOneTimePreKey(Optional.<ECKeyPair>absent())
                .setOurIdentityKey(bobIdentityKey)
                .setTheirIdentityKey(aliceIdentityKey.getPublicKey())
                .setTheirBaseKey(aliceBaseKey.getPublicKey())
                .create();

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
     * Encrypts with StreamedProcess
     * Decrypts with Array
     */
    @Test
    public void test1ArrayEncryptArrayDecrypt() throws Exception {
        String message = "Hello, World!".repeat(9999);
        SignalMessage signalm = (SignalMessage) aliceCipher.encrypt(message.getBytes());
        byte[] plaintext = bobCipher.decrypt(signalm);
        String decrypted = new String(plaintext, StandardCharsets.UTF_8);
        Assertions.assertEquals(message, decrypted);
    }

    @Test
    public void test3StreamingEncryptStreamingDecrypt() throws Exception {
        Path tempPathOut = File.createTempFile("TestingFileOut", ".txt").toPath();
        Path tempPathIn = File.createTempFile("TestingFileIn", ".txt").toPath();

        String message = "Hello, World!".repeat(10000);
        OutputStream cipherText = Files.newOutputStream(tempPathIn);
        aliceCipher.encrypt(new ByteArrayInputStream(message.getBytes()), cipherText);
        OutputStream outputStream = Files.newOutputStream(tempPathOut);

        bobCipher.decrypt(tempPathIn, tempPathOut);
        String decrypted = Files.readString(tempPathOut, StandardCharsets.UTF_8);
        Assertions.assertEquals(message, decrypted);
    }

    /** the routing peer ID is the SHA-1 based ID of the TLS key's X.509 DER */
    @Test
    public void peerIdForTlsKeyIsGenerateIdOfDer() throws Exception {
        PublicKey key = DDDTLSUtil.generateKeyPair().getPublic();
        String peerId = SecurityUtils.peerIdForTlsKey(key);
        Assertions.assertEquals(SecurityUtils.generateID(key.getEncoded()), peerId);
        Assertions.assertEquals(peerId, SecurityUtils.peerIdForTlsKey(key.getEncoded()));
        Assertions.assertEquals(28, peerId.length());
    }

    /** the legacy importer relies on this: a {@code publicKeyToName} row converts offline to the peer ID */
    @Test
    public void legacyTlsNameConvertsToPeerId() throws Exception {
        for (int i = 0; i < 50; i++) {
            PublicKey key = DDDTLSUtil.generateKeyPair().getPublic();
            String legacyName = DDDTLSUtil.publicKeyToName(key);
            Assertions.assertEquals(SecurityUtils.peerIdForTlsKey(key), SecurityUtils.generateID(legacyName));
        }
    }

    /** a dropbox's own recency blob names the same peer ID the server routes by */
    @Test
    public void recencySignerSenderIdEqualsPeerId() throws Exception {
        PublicKey key = DDDTLSUtil.generateKeyPair().getPublic();
        Assertions.assertEquals(SecurityUtils.peerIdForTlsKey(key),
                                RecencyRequestSigner.senderIdFor(Base64.getEncoder().encodeToString(key.getEncoded())));
    }
}
