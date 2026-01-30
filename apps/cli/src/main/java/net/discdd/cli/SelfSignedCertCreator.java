package net.discdd.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.Callable;

import net.discdd.tls.DDDTLSUtil;

import picocli.CommandLine;

@CommandLine.Command(name = "create-cert", description = "Create a self-signed certificate")
public class SelfSignedCertCreator implements Callable<Void> {
    @CommandLine.Option(names = "--pub", required = true, description = "Public key file path")
    private Path publicKeyPath;
    @CommandLine.Option(names = "--pvt", required = true, description = "Private key file path")
    private Path privateKeyPath;
    @CommandLine.Option(names = "--cert", required = true, description = "Certificate file path")
    private Path certPath;

    @Override
    public Void call() throws IOException, NoSuchAlgorithmException, java.security.InvalidKeyException,
            InvalidKeySpecException, NoSuchProviderException {
        // Create a self-signed certificate
        KeyPair keyPair = DDDTLSUtil.loadKeyPairfromFiles(publicKeyPath, privateKeyPath);

        try {
            var cert = DDDTLSUtil.getSelfSignedCertificate(keyPair, DDDTLSUtil.publicKeyToName(keyPair.getPublic()));
            DDDTLSUtil.writeCertToFile(cert, certPath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

}
