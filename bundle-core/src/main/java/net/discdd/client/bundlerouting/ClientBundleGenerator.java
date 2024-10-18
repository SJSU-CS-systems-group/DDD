package net.discdd.client.bundlerouting;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.pathutils.ClientPaths;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class ClientBundleGenerator {

    private static final Logger logger = Logger.getLogger(ClientBundleGenerator.class.getName());

    static ClientBundleGenerator singleGeneratorInstance = null;
    ClientSecurity clientSecurity;

    /* Counter value used as unsigned long */
    private long currentCounter = 0;

    private ClientPaths clientPaths;

    private ClientBundleGenerator(ClientSecurity clientSecurity, ClientPaths clientPaths) throws IOException {
        this.clientSecurity = clientSecurity;
        this.clientPaths = clientPaths;

        try {
            byte[] counterFromFile = Files.readAllBytes(clientPaths.counterFilePath);
            currentCounter = Long.parseUnsignedLong(new String(counterFromFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            updateBundleIDFile();
        }
    }

    private void updateBundleIDFile() throws IOException {
        clientPaths.counterFilePath.getParent().toFile().mkdirs();
        Files.write(clientPaths.counterFilePath, Long.toUnsignedString(currentCounter).getBytes());
    }

    synchronized public static ClientBundleGenerator initializeInstance(ClientSecurity clientSecurity,
                                                                        ClientPaths clientPaths) throws IOException {
        if (singleGeneratorInstance == null) {
            singleGeneratorInstance = new ClientBundleGenerator(clientSecurity, clientPaths);
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

    public int compareBundleIDs(String id1, String id2, boolean direction) throws GeneralSecurityException,
            InvalidKeyException {
        if (Objects.equals(id2, "0")) {
            String decryptedBundleID1 = clientSecurity.decryptBundleID(id1);
            var foo = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID1, direction);

            return Long.compare(foo, Long.parseLong(id2));
        }

        String decryptedBundleID1 = clientSecurity.decryptBundleID(id1);
        String decryptedBundleID2 = clientSecurity.decryptBundleID(id2);

        var counterId1 = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID1, direction);
        var counterId2 = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID2, direction);

        return Long.compare(counterId1, counterId2);
    }

    public long getCounterFromBundleID(String bundleID, boolean direction) throws GeneralSecurityException,
            InvalidKeyException {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}
