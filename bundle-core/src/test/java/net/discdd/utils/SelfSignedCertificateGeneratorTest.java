package net.discdd.utils;

import org.junit.jupiter.api.Test;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SelfSignedCertificateGeneratorTest {

    private static SSLServerSocket sslServerSocket;

    @Test
    public void testGenerateSelfSignedCertificateWithSSL() throws Exception {
        // Generate EC key pair
        ECKeyPair ecKeyPair = Curve.generateKeyPair();
        ECPrivateKey privateKey = ecKeyPair.getPrivateKey();
        ECPublicKey publicKey = ecKeyPair.getPublicKey();

        // Generate self-signed certificate
        X509Certificate certificate = SelfSignedCertificateGenerator.generateSelfSignedCertificate(publicKey, privateKey);
        System.out.println("Certificate: " + certificate);
        assertNotNull(certificate);

        // Set up KeyStore with the certificate and private key
        KeyStore keyStore = KeyStore.getInstance("PKCS12"); // or "JKS"
        keyStore.load(null, null);
        keyStore.setKeyEntry("test", SelfSignedCertificateGenerator.convertToPrivateKey(privateKey), "password".toCharArray(), new X509Certificate[]{certificate});

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "password".toCharArray());

        // Initialize TrustManagerFactory
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);

        // Initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        // Create SSLServerSocket
        SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();

        String[] cipherSuite = {
                "TLS_AES_128_GCM_SHA256",
//                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
//                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
//                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
//                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        };

        // Accept connections and verify SSL handshake
        new Thread(() -> {
            try (SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(8333)) {
                sslServerSocket.setEnabledCipherSuites(cipherSuite);
                sslServerSocket.setNeedClientAuth(true);
                sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
                System.out.println("Enabled cipher suites: " + Arrays.toString(sslServerSocket.getSupportedCipherSuites()));
                try (SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept()) {
                    InputStream is = new BufferedInputStream(sslSocket.getInputStream());
                    byte[] buffer = new byte[1024];
                    is.read(buffer);
                    System.out.println("Received: " + new String(buffer));

                    OutputStream os = new BufferedOutputStream(sslSocket.getOutputStream());
                    os.write("Hello Client!".getBytes());
                    os.flush();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // Client side to test the server
        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

        try (SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", 8333)) {
            sslSocket.setEnabledCipherSuites(cipherSuite);
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});

            InputStream is = new BufferedInputStream(sslSocket.getInputStream());
            byte[] buffer = new byte[1024];
            is.read(buffer);
            System.out.println("Received: " + new String(buffer));

            OutputStream os = new BufferedOutputStream(sslSocket.getOutputStream());
            os.write("Hello Server!".getBytes());
            os.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
