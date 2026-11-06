package net.discdd.client.bundletransmission;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ClientBundleTransmission#boundPeerId}: a blob is bound to the TLS peer only if it names the peer's key */
class RecencyBindingTest {
    private static X509Certificate peerCert;
    private static KeyPair otherKey;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair peerKey = DDDTLSUtil.generateKeyPair();
        X509Certificate cert = DDDTLSUtil.getSelfSignedCertificate(peerKey, DDDTLSUtil.publicKeyToName(peerKey.getPublic()));
        // parse it back as the TLS stack would hand it over
        peerCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.getEncoded()));
        otherKey = DDDTLSUtil.generateKeyPair();
    }

    private static GetRecencyBlobResponse blobNaming(String senderId) {
        return GetRecencyBlobResponse.newBuilder()
                .setRecencyBlob(RecencyBlob.newBuilder().setSenderId(senderId).setBlobTimestamp(1))
                .build();
    }

    @Test
    void boundOnlyWhenBlobNamesPeerKey() {
        String peerId = SecurityUtils.peerIdForTlsKey(peerCert.getPublicKey());
        assertEquals(peerId, ClientBundleTransmission.boundPeerId(blobNaming(peerId), peerCert));

        String otherId = SecurityUtils.peerIdForTlsKey(otherKey.getPublic());
        assertNull(ClientBundleTransmission.boundPeerId(blobNaming(otherId), peerCert),
                   "a blob minted for another key is not this peer's");
        assertNull(ClientBundleTransmission.boundPeerId(blobNaming(DDDTLSUtil.publicKeyToName(peerCert.getPublicKey())),
                                                        peerCert), "the legacy name form is not a peer ID");
    }

    @Test
    void nullCertOrEmptySenderUnbound() {
        String peerId = SecurityUtils.peerIdForTlsKey(peerCert.getPublicKey());
        assertNull(ClientBundleTransmission.boundPeerId(blobNaming(peerId), null));
        assertNull(ClientBundleTransmission.boundPeerId(blobNaming(""), peerCert));
        assertNull(ClientBundleTransmission.boundPeerId(GetRecencyBlobResponse.getDefaultInstance(), peerCert));
        assertNull(ClientBundleTransmission.boundPeerId(null, peerCert));
    }
}
