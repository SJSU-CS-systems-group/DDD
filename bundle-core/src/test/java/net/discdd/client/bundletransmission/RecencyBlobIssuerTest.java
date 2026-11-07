package net.discdd.client.bundletransmission;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.RecencyBlob;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;

public class RecencyBlobIssuerTest {

    private static X509Certificate certFor(KeyPair keyPair) throws Exception {
        return DDDTLSUtil.getSelfSignedCertificate(keyPair, DDDTLSUtil.publicKeyToName(keyPair.getPublic()));
    }

    // the senderId the server puts in a blob for a transport using this key
    private static RecencyBlob blobIssuedTo(KeyPair keyPair) {
        return RecencyBlob.newBuilder()
                .setSenderId(SecurityUtils.generateID(keyPair.getPublic().getEncoded()))
                .build();
    }

    @Test
    void blobFromTheConnectedTransportMatches() throws Exception {
        var transport = DDDTLSUtil.generateKeyPair();
        Assertions.assertTrue(ClientBundleTransmission.isBlobIssuedTo(blobIssuedTo(transport), certFor(transport)));
    }

    @Test
    void blobCopiedFromAnotherTransportDoesNotMatch() throws Exception {
        var realTransport = DDDTLSUtil.generateKeyPair();
        var otherDevice = DDDTLSUtil.generateKeyPair();
        Assertions.assertFalse(ClientBundleTransmission.isBlobIssuedTo(blobIssuedTo(realTransport),
                                                                       certFor(otherDevice)));
    }

    @Test
    void noPeerCertificateDoesNotMatch() throws Exception {
        var transport = DDDTLSUtil.generateKeyPair();
        Assertions.assertFalse(ClientBundleTransmission.isBlobIssuedTo(blobIssuedTo(transport), null));
    }
}
