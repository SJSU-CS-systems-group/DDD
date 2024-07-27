package net.discdd.client.bundlerouting;

import static java.util.logging.Level.WARNING;

import net.discdd.bundlesecurity.BundleIDGenerator;

import net.discdd.client.bundlesecurity.ClientSecurity;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

public class ClientBundleGenerator {

    private static final Logger logger = Logger.getLogger(ClientBundleGenerator.class.getName());

    static ClientBundleGenerator singleGeneratorInstance = null;
    ClientSecurity clientSecurity;

    /* Counter value used as unsigned long */
    private long currentCounter = 0;

    final private Path counterFilePath;

    private ClientBundleGenerator(ClientSecurity clientSecurity, Path rootPath) throws IOException {
        this.clientSecurity = clientSecurity;
        counterFilePath = rootPath.resolve(Paths.get("BundleRouting", "sentBundle.id"));

        try {
            byte[] counterFromFile = Files.readAllBytes(counterFilePath);
            currentCounter = Long.parseUnsignedLong(new String(counterFromFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            updateBundleIDFile();
        }
    }

    private void updateBundleIDFile() throws IOException {
        counterFilePath.getParent().toFile().mkdirs();
        Files.write(counterFilePath, Long.toUnsignedString(currentCounter).getBytes());
    }

    synchronized public static ClientBundleGenerator initializeInstance(ClientSecurity clientSecurity, Path rootPath) throws IOException {
        if (singleGeneratorInstance == null) {
            singleGeneratorInstance = new ClientBundleGenerator(clientSecurity, rootPath);
        } else {
            logger.log(WARNING, "[BR]: Client bundle generator instance is already created!");
        }
        return singleGeneratorInstance;
    }

    synchronized public static ClientBundleGenerator getInstance() throws IllegalStateException {
        if (singleGeneratorInstance == null) {
            throw new IllegalStateException("Client Bundle Generator has not been initialized!");
        }
        return singleGeneratorInstance;
    }

    public String generateBundleID() throws IOException, InvalidKeyException, GeneralSecurityException {
        String clientID = clientSecurity.getClientID();
        currentCounter++;

        String plainBundleID = BundleIDGenerator.generateBundleID(clientID, currentCounter, BundleIDGenerator.UPSTREAM);
        updateBundleIDFile();
        return clientSecurity.encryptBundleID(plainBundleID);
    }

    public int compareBundleIDs(String id1, long id2, boolean direction) throws GeneralSecurityException,
            InvalidKeyException {
        String decryptedBundleID1 = clientSecurity.decryptBundleID(id1);
        var foo = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID1, direction);

        return Long.compare(foo, id2);
    }

    public long getCounterFromBundleID(String bundleID, boolean direction) throws GeneralSecurityException,
            InvalidKeyException {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}
