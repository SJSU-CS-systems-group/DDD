package net.discdd.tls;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

public class DDDX509ExtendedTrustManager extends X509ExtendedTrustManager {
    private final boolean singleCert;

    /**
     * Creates a new DDDX509ExtendedTrustManager that will pin itself to the first certificate it finds.
     *
     * @param singleCert - for the life time of this object, only a single certificate will be accepted.
     *                   it may be checked multiple times, but it must always be the same certificate.
     *                   this should be true for clients, and false for servers since server will probably
     *                   talk to multiple clients using this trust manager.
     */
    public DDDX509ExtendedTrustManager(boolean singleCert) {
        this.singleCert = singleCert;
    }

    private void checkCertificate(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("No certificate present");
        if (chain.length != 1) throw new CertificateException("No chained certificates expected");
        var cert = chain[0];
        var algorithm = cert.getPublicKey().getAlgorithm();
        if (!algorithm.equals("Ed25519") && !algorithm.equals("EdDSA") && !algorithm.equals("SHA256withECDSA") &&
                !algorithm.equals("ECDSA") && !algorithm.equals("EC")) {
            throw new CertificateException("Only Ed25519 certificates are accepted not " + algorithm);
        }
        var expectedCN = "CN=" + DDDTLSUtil.publicKeyToName(cert.getPublicKey());
        var actualCN = cert.getSubjectX500Principal().getName();
        if (!actualCN.equals(expectedCN)) {
            throw new CertificateException("Subject name does not match public key: " + actualCN + " != " + expectedCN);
        }
        if (singleCert && checkedCert != null && !cert.equals(checkedCert)) {
            throw new CertificateException("Certificate does not match previously checked certificate");
        }
        // all good!
        checkedCert = cert;
    }

    private X509Certificate checkedCert = null;

    public X509Certificate getCheckedCert() {
        return checkedCert;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws
            CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws
            CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws
            CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws
            CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checkCertificate(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}