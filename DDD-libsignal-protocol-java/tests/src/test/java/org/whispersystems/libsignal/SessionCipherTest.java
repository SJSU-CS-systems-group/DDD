package org.whispersystems.libsignal;

import junit.framework.TestCase;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.util.guava.Optional;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;


public class SessionCipherTest extends TestCase {

  public void testBasicSessionV3()
      throws InvalidKeyException, DuplicateMessageException,
      LegacyMessageException, InvalidMessageException, NoSuchAlgorithmException, NoSessionException, UntrustedIdentityException
  {
    SessionRecord aliceSessionRecord = new SessionRecord();
    SessionRecord bobSessionRecord   = new SessionRecord();

    initializeSessionsV3(aliceSessionRecord.getSessionState(), bobSessionRecord.getSessionState());
    runInteraction(aliceSessionRecord, bobSessionRecord);
  }

  public void testMessageKeyLimits() throws Exception {
    SessionRecord aliceSessionRecord = new SessionRecord();
    SessionRecord bobSessionRecord   = new SessionRecord();

    initializeSessionsV3(aliceSessionRecord.getSessionState(), bobSessionRecord.getSessionState());

    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();

    aliceStore.storeSession(new SignalProtocolAddress("+14159999999", 1), aliceSessionRecord);
    bobStore.storeSession(new SignalProtocolAddress("+14158888888", 1), bobSessionRecord);

    SessionCipher     aliceCipher    = new SessionCipher(aliceStore, new SignalProtocolAddress("+14159999999", 1));
    SessionCipher     bobCipher      = new SessionCipher(bobStore, new SignalProtocolAddress("+14158888888", 1));

    List<CiphertextMessage> inflight = new LinkedList<>();

    for (int i=0;i<2010;i++) {
      inflight.add(aliceCipher.encrypt("you've never been so hungry, you've never been so cold".getBytes()));
    }

    bobCipher.decrypt(new SignalMessage(inflight.get(1000).serialize()));
    bobCipher.decrypt(new SignalMessage(inflight.get(inflight.size()-1).serialize()));

    try {
      bobCipher.decrypt(new SignalMessage(inflight.get(0).serialize()));
      throw new AssertionError("Should have failed!");
    } catch (DuplicateMessageException dme) {
      // good
    }
  }

  private void runInteraction(SessionRecord aliceSessionRecord, SessionRecord bobSessionRecord)
      throws DuplicateMessageException, LegacyMessageException, InvalidMessageException, NoSuchAlgorithmException, NoSessionException, UntrustedIdentityException {
    SignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    SignalProtocolStore bobStore   = new TestInMemorySignalProtocolStore();

    aliceStore.storeSession(new SignalProtocolAddress("+14159999999", 1), aliceSessionRecord);
    bobStore.storeSession(new SignalProtocolAddress("+14158888888", 1), bobSessionRecord);

    SessionCipher     aliceCipher    = new SessionCipher(aliceStore, new SignalProtocolAddress("+14159999999", 1));
    SessionCipher     bobCipher      = new SessionCipher(bobStore, new SignalProtocolAddress("+14158888888", 1));

    byte[]            alicePlaintext = "This is a plaintext message.".getBytes();
    CiphertextMessage message        = aliceCipher.encrypt(alicePlaintext);
    byte[]            bobPlaintext   = bobCipher.decrypt(new SignalMessage(message.serialize()));

    assertTrue(Arrays.equals(alicePlaintext, bobPlaintext));

    byte[]            bobReply      = "This is a message from Bob.".getBytes();
    CiphertextMessage reply         = bobCipher.encrypt(bobReply);
    byte[]            receivedReply = aliceCipher.decrypt(new SignalMessage(reply.serialize()));

    assertTrue(Arrays.equals(bobReply, receivedReply));

    List<CiphertextMessage> aliceCiphertextMessages = new ArrayList<>();
    List<byte[]>            alicePlaintextMessages  = new ArrayList<>();

    for (int i=0;i<50;i++) {
      alicePlaintextMessages.add(("смерть за смерть " + i).getBytes());
      aliceCiphertextMessages.add(aliceCipher.encrypt(("смерть за смерть " + i).getBytes()));
    }

    long seed = System.currentTimeMillis();

    Collections.shuffle(aliceCiphertextMessages, new Random(seed));
    Collections.shuffle(alicePlaintextMessages, new Random(seed));

    for (int i=0;i<aliceCiphertextMessages.size() / 2;i++) {
      byte[] receivedPlaintext = bobCipher.decrypt(new SignalMessage(aliceCiphertextMessages.get(i).serialize()));
      assertTrue(Arrays.equals(receivedPlaintext, alicePlaintextMessages.get(i)));
    }

    List<CiphertextMessage> bobCiphertextMessages = new ArrayList<>();
    List<byte[]>            bobPlaintextMessages  = new ArrayList<>();

    for (int i=0;i<20;i++) {
      bobPlaintextMessages.add(("смерть за смерть " + i).getBytes());
      bobCiphertextMessages.add(bobCipher.encrypt(("смерть за смерть " + i).getBytes()));
    }

    seed = System.currentTimeMillis();

    Collections.shuffle(bobCiphertextMessages, new Random(seed));
    Collections.shuffle(bobPlaintextMessages, new Random(seed));

    for (int i=0;i<bobCiphertextMessages.size() / 2;i++) {
      byte[] receivedPlaintext = aliceCipher.decrypt(new SignalMessage(bobCiphertextMessages.get(i).serialize()));
      assertTrue(Arrays.equals(receivedPlaintext, bobPlaintextMessages.get(i)));
    }

    for (int i=aliceCiphertextMessages.size()/2;i<aliceCiphertextMessages.size();i++) {
      byte[] receivedPlaintext = bobCipher.decrypt(new SignalMessage(aliceCiphertextMessages.get(i).serialize()));
      assertTrue(Arrays.equals(receivedPlaintext, alicePlaintextMessages.get(i)));
    }

    for (int i=bobCiphertextMessages.size() / 2;i<bobCiphertextMessages.size(); i++) {
      byte[] receivedPlaintext = aliceCipher.decrypt(new SignalMessage(bobCiphertextMessages.get(i).serialize()));
      assertTrue(Arrays.equals(receivedPlaintext, bobPlaintextMessages.get(i)));
    }
  }

  private void initializeSessionsV3(SessionState aliceSessionState, SessionState bobSessionState)
      throws InvalidKeyException
  {
    ECKeyPair       aliceIdentityKeyPair = Curve.generateKeyPair();
    IdentityKeyPair aliceIdentityKey     = new IdentityKeyPair(new IdentityKey(aliceIdentityKeyPair.getPublicKey()),
                                                               aliceIdentityKeyPair.getPrivateKey());
    ECKeyPair       aliceBaseKey         = Curve.generateKeyPair();
    ECKeyPair       aliceEphemeralKey    = Curve.generateKeyPair();

    ECKeyPair alicePreKey = aliceBaseKey;

    ECKeyPair       bobIdentityKeyPair = Curve.generateKeyPair();
    IdentityKeyPair bobIdentityKey       = new IdentityKeyPair(new IdentityKey(bobIdentityKeyPair.getPublicKey()),
                                                               bobIdentityKeyPair.getPrivateKey());
    ECKeyPair       bobBaseKey           = Curve.generateKeyPair();
    ECKeyPair       bobEphemeralKey      = bobBaseKey;

    ECKeyPair       bobPreKey            = Curve.generateKeyPair();

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

}
