package net.discdd.dropbox.control;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests {@link RecencyRequestSigner} with real TLS key pairs from {@code DDDTLSUtil.generateKeyPair}. */
class RecencyRequestSignerTest {
    private static final String CLIENT_ID = "Zm9vYmFyYmF6cXV4cXV1eGNvcmdl";
    private static KeyPair tls;
    private static KeyPair otherTls;

    @BeforeAll
    static void keys() throws Exception {
        tls = DDDTLSUtil.generateKeyPair();
        otherTls = DDDTLSUtil.generateKeyPair();
    }

    /** Flips one bit of the decoded value and re-encodes it. */
    private static String flipBit(String b64, int byteIndex) {
        byte[] b = Base64.getDecoder().decode(b64);
        b[byteIndex] ^= 1;
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void signedBytesAreTheDocumentedFormat() {
        byte[] bytes = RecencyRequestSigner.signedBytes("cid", 12, "KEY=");
        assertArrayEquals("net.discdd.dropbox.recency-request.v1\ncid\n12\nKEY=".getBytes(StandardCharsets.UTF_8),
                          bytes);
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes(null, 1, "K"));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes("", 1, "K"));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes("a\nb", 1, "K"));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes("c", 0, "K"));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes("c", 1, null));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.signedBytes("c", 1, "K\n"));
    }

    @Test
    void signThenVerifyAcceptsAndSurvivesTheCodec() throws Exception {
        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 5, tls);
        assertEquals(5, r.getRequestSeq());
        assertArrayEquals(tls.getPublic().getEncoded(), r.getTlsPublicKeyDer());
        assertTrue(RecencyRequestSigner.verify(CLIENT_ID, r));

        RecencyRequest decoded = DropboxControlCodec.decodeRecencyRequest(DropboxControlCodec.encode(r));
        assertTrue(RecencyRequestSigner.verify(CLIENT_ID, decoded));
    }

    @Test
    void tamperedRequestsAreRejected() throws Exception {
        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 5, tls);
        // Another endpoint replaying the request.
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID + "x", r));
        // Another sequence number.
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID,
                                                new RecencyRequest(6, r.getTlsPublicKey(), r.getSignature())));
        // A key the signer does not hold, with the original signature.
        String otherKey = Base64.getEncoder().encodeToString(otherTls.getPublic().getEncoded());
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, otherKey, r.getSignature())));
        // A tampered signature.
        String sig = r.getSignature();
        int last = Base64.getDecoder().decode(sig).length - 1;
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID,
                                                new RecencyRequest(5, r.getTlsPublicKey(), flipBit(sig, last))));
        // A signature by another key over the same bytes.
        RecencyRequest byOther = RecencyRequestSigner.sign(CLIENT_ID, 5, otherTls);
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID,
                                                new RecencyRequest(5, r.getTlsPublicKey(), byOther.getSignature())));
    }

    @Test
    void garbageKeysAndSignaturesAreRejectedWithoutThrowing() throws Exception {
        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 5, tls);
        String junk = Base64.getEncoder().encodeToString("definitely not a DER key".getBytes(StandardCharsets.UTF_8));
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, junk, r.getSignature())));
        // A key whose DER is cut short.
        String truncated = Base64.getEncoder().encodeToString(Arrays.copyOf(tls.getPublic().getEncoded(), 40));
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, truncated, r.getSignature())));
        // A flipped bit inside the EC point: no longer on the curve, or a different key.
        String badPoint = flipBit(r.getTlsPublicKey(), tls.getPublic().getEncoded().length - 1);
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, badPoint, r.getSignature())));
        // A signature that is not DER.
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, r.getTlsPublicKey(), "AAAA")));
        // Fields the codec would reject never reach the crypto.
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, "not base64!", r.getSignature())));
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(5, r.getTlsPublicKey(), null)));
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID,
                                                new RecencyRequest(0, r.getTlsPublicKey(), r.getSignature())));
        assertFalse(RecencyRequestSigner.verify(null, r));
        assertFalse(RecencyRequestSigner.verify("", r));
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, null));
    }

    @Test
    void nonEcKeysAreRejected() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsa = rsaGen.generateKeyPair();
        // Signing with an RSA key is refused by SHA256withECDSA.
        assertThrows(GeneralSecurityException.class, () -> RecencyRequestSigner.sign(CLIENT_ID, 1, rsa));
        String rsaKey = Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded());
        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 1, tls);
        assertFalse(RecencyRequestSigner.verify(CLIENT_ID, new RecencyRequest(1, rsaKey, r.getSignature())));
    }

    @Test
    void senderIdIsGenerateIdOfTheDer() throws Exception {
        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 1, tls);
        String senderId = RecencyRequestSigner.senderIdFor(r.getTlsPublicKey());
        assertEquals(SecurityUtils.generateID(tls.getPublic().getEncoded()), senderId);
        assertEquals(SecurityUtils.generateID(r.getTlsPublicKeyDer()), senderId);
        assertNotEquals(RecencyRequestSigner.senderIdFor(RecencyRequestSigner.sign(CLIENT_ID, 1, otherTls)
                                                                 .getTlsPublicKey()), senderId);
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.senderIdFor(null));
        assertThrows(IllegalArgumentException.class, () -> RecencyRequestSigner.senderIdFor("not base64!"));
    }

    @Test
    void senderIdMatchesWhatTheGrpcServerComputesForTheCertificate() throws Exception {
        // The dropbox presents a self-signed cert for its TLS key (GrpcSecurityKey). The server's gRPC
        // getRecencyBlob computes senderId = generateID(peerCert.getPublicKey().getEncoded()) from the cert it
        // receives on the wire, so parse the cert back from its encoding as the TLS stack would.
        X509Certificate cert =
                DDDTLSUtil.getSelfSignedCertificate(tls, DDDTLSUtil.publicKeyToName(tls.getPublic()));
        X509Certificate onTheWire = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.getEncoded()));
        String grpcSenderId = SecurityUtils.generateID(onTheWire.getPublicKey().getEncoded());

        RecencyRequest r = RecencyRequestSigner.sign(CLIENT_ID, 1, tls);
        assertEquals(grpcSenderId, RecencyRequestSigner.senderIdFor(r.getTlsPublicKey()));
    }
}
