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
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SelfSignedCertificateGeneratorTest {
    @Test
    public void testGenerateSelfSignedCertificateWithSSL() throws Exception {
        // Generate EC key pair
        ECKeyPair ecKeyPair = Curve.generateKeyPair();
        ECPrivateKey privateKey = ecKeyPair.getPrivateKey();
        ECPublicKey publicKey = ecKeyPair.getPublicKey();

        // Generate self-signed certificate
        X509Certificate certificate = SelfSignedCertificateGenerator.generateSelfSignedCertificate(publicKey, privateKey);
        assertNotNull(certificate);

        // Set up KeyStore with the certificate and private key
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("test", SelfSignedCertificateGenerator.convertToPrivateKey(privateKey), "password".toCharArray(), new X509Certificate[]{certificate});

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "password".toCharArray());

        // Initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

        // Create SSLServerSocket
        SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
        SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
        int port = sslServerSocket.getLocalPort();
        // Accept connections and verify SSL handshake
        new Thread(() -> {
            try (SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept()) {
                InputStream inputStream = sslSocket.getInputStream();
                OutputStream outputStream = sslSocket.getOutputStream();
                outputStream.write("Hello, SSL!".getBytes());
                outputStream.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // Client side to test the server
        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", port)) {
            InputStream inputStream = sslSocket.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = inputStream.read(buffer);
            String response = new String(buffer, 0, bytesRead);
            System.out.println("Received from server: " + response);
        }
    }
}
