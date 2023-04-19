package com.ddd.client.bundlerouting;

import com.ddd.client.bundlesecurity.BundleIDGenerator;
import com.ddd.client.bundlesecurity.ClientSecurity;
import com.ddd.client.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.client.bundlesecurity.SecurityExceptions.IDGenerationException;

public class ClientBundleGenerator {
    static ClientBundleGenerator singleGeneratorInstance = null;
    BundleIDGenerator bundleIDGenerator           = null;
    ClientSecurity clientSecurity                 = null;

    private ClientBundleGenerator(ClientSecurity clientSecurity)
    {
        this.clientSecurity = clientSecurity;
        bundleIDGenerator   = new BundleIDGenerator();
    }

    public static ClientBundleGenerator getInstance(ClientSecurity clientSecurity)
    {
        if (singleGeneratorInstance == null) {
            singleGeneratorInstance = new ClientBundleGenerator(clientSecurity);
        }
        return singleGeneratorInstance;
    }

    public String generateBundleID(String clientKeyPath, boolean upstream) throws IDGenerationException, BundleIDCryptographyException
    {
        String plainBundleID = bundleIDGenerator.generateBundleID(clientSecurity.getClientKeyPath(), BundleIDGenerator.UPSTREAM);
        return clientSecurity.encryptBundleID(plainBundleID);
    }

    public int compareBundleIDs(String id1, String id2, boolean direction) throws BundleIDCryptographyException
    {
        String decryptedBundleID1 = clientSecurity.decryptBundleID(id1);
        String decryptedBundleID2 = clientSecurity.decryptBundleID(id2);

        return BundleIDGenerator.compareBundleIDs(decryptedBundleID1, decryptedBundleID2, direction);
    }

    public long getCounterFromBundleID(String bundleID, boolean direction) throws BundleIDCryptographyException
    {
        String decryptedBundleID = clientSecurity.decryptBundleID(bundleID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}
