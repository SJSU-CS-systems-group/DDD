package net.discdd.cli;

import net.discdd.tls.DDDTLSUtil;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@CommandLine.Command(name = "create-cert", description = "Create a self-signed certificate")
public class SelfSignedCertCreator implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(SelfSignedCertCreator.class.getName());
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
            logger.log(SEVERE, "Failed to create self-signed certificate at " + certPath, e);
        }
        return null;
    }

}
