package com.ddd.client.bundlerouting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;


import com.ddd.bundlesecurity.BundleIDGenerator;

import com.ddd.client.bundlesecurity.ClientSecurity;
import com.ddd.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.bundlesecurity.SecurityUtils;

public class ClientBundleGenerator {

    private static final Logger logger = Logger.getLogger(ClientBundleGenerator.class.getName());

    static ClientBundleGenerator singleGeneratorInstance = null;
    ClientSecurity clientSecurity = null;

    /* Counter value used as unsigned long */
    private long currentCounter = 0;

    private String counterFilePath = null;

    private ClientBundleGenerator(ClientSecurity clientSecurity, String rootPath) {
        this.clientSecurity = clientSecurity;
        counterFilePath = rootPath + File.separator + "BundleRouting" + File.separator + "sentBundle.id";

        try {
            byte[] counterFromFile = SecurityUtils.readFromFile(counterFilePath);
            currentCounter = Long.parseUnsignedLong(new String(counterFromFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            updateBundleIDFile();
        }
    }

    private void updateBundleIDFile() {
        try (FileOutputStream stream = new FileOutputStream(counterFilePath)) {
            stream.write(Long.toUnsignedString(currentCounter).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            logger.log(WARNING, "[BR]: Failed to create counter backup file! " + ex);
        }
    }

    public static ClientBundleGenerator initializeInstance(ClientSecurity clientSecurity, String rootPath) {
        if (singleGeneratorInstance == null) {
            singleGeneratorInstance = new ClientBundleGenerator(clientSecurity, rootPath);
        } else {
            logger.log(WARNING, "[BR]: Client bundle generator instance is already created!");
        }
        return singleGeneratorInstance;
    }

    public static ClientBundleGenerator getInstance() throws IllegalStateException {
        if (singleGeneratorInstance == null) {
            throw new IllegalStateException("Client Bundle Generator has not been initialized!");
        }
        return singleGeneratorInstance;
    }

    public String generateBundleID() throws IDGenerationException, BundleIDCryptographyException {
        String clientID = clientSecurity.getClientID();
        currentCounter++;

        String plainBundleID = BundleIDGenerator.generateBundleID(clientID, currentCounter, BundleIDGenerator.UPSTREAM);
        updateBundleIDFile();
        return clientSecurity.encryptBundleID(plainBundleID);
    }

    public int compareBundleIDs(String id1, String id2, boolean direction) throws BundleIDCryptographyException {
        String decryptedBundleID1 = clientSecurity.decryptBundleID(id1);
        String decryptedBundleID2 = clientSecurity.decryptBundleID(id2);

        return BundleIDGenerator.compareBundleIDs(decryptedBundleID1, decryptedBundleID2, direction);
    }

    public long getCounterFromBundleID(String bundleID, boolean direction) throws BundleIDCryptographyException {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}
