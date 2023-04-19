package com.ddd.server.bundlerouting;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import com.ddd.server.bundlesecurity.BundleIDGenerator;
import com.ddd.server.bundlesecurity.ServerSecurity;
import com.ddd.server.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.server.bundlesecurity.SecurityExceptions.InvalidClientIDException;

import com.ddd.server.bundlerouting.WindowUtils.Window;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.ClientAlreadyExists;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidBundleID;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.RecievedInvalidACK;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.RecievedOldACK;

@Service
public class ServerWindow {
    private HashMap<String, Window> clientHashMap;
    ServerSecurity                  serverSecurity;

    public ServerWindow(ServerSecurity serverSecurity)
    {
        clientHashMap       = new HashMap<>();
        this.serverSecurity = serverSecurity;
    }

    /* Returns the window for the requested client
     * Parameters:
     * clientID     : encoded clientID
     * Returns:
     * Window object
     */
    private Window getClientWindow(String clientID) throws ClientWindowNotFound
    {
        if (!clientHashMap.containsKey(clientID)) {
            throw new ClientWindowNotFound("ClientID["+clientID+"] Not Found");
        }

        return clientHashMap.get(clientID);
    }

    /* Add a new client and initialize its window
     * Parameters:
     * clientID     : encoded clientID
     * windowLength : length of the window to be created
     * Returns:
     * None
     */
    public void addClient(String clientID, int windowLength) throws InvalidLength, ClientAlreadyExists
    {
        if (clientHashMap.containsKey(clientID)) {
            throw new ClientAlreadyExists("[BR]: Cannot Add to Map; client already exists");
        }
        clientHashMap.put(clientID, new Window(windowLength));
    }

    /* Commits the bundleID to the client's window
     * Parameter:
     * clientID     : encoded clientID
     * bundleID     : encoded bundleID
     * Returns:
     * None
     */
    public void updateClientWindow(String clientID, String bundleID) throws ClientWindowNotFound, BufferOverflow, InvalidBundleID, BundleIDCryptographyException
    {
        String decryptedBundleID = serverSecurity.decryptBundleID(bundleID, clientID);
        getClientWindow(clientID).add(decryptedBundleID);
    }

    public String getCurrentbundleID(String clientID) throws ClientWindowNotFound, BundleIDCryptographyException, InvalidClientIDException
    {
        String plainBundleID = getClientWindow(clientID).getCurrentbundleID(clientID);
        return serverSecurity.encryptBundleID(plainBundleID, clientID);
    }

    /* Return the latest bundleID in the client's window
     * Parameter:
     * clientID     : encoded clientID
     * Returns:
     * Latest bundle ID in window
     */
    public String getLatestClientBundle(String clientID) throws ClientWindowNotFound
    {
        return getClientWindow(clientID).getLatestBundleID();
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (encrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackedBundleID) throws ClientWindowNotFound, InvalidLength, BundleIDCryptographyException
    {
        Window clientWindow = getClientWindow(clientID);
        String decryptedBundleID = serverSecurity.decryptBundleID(ackedBundleID, clientID);
        System.out.println("Decrypted Ack from file = "+decryptedBundleID);
        long ack = BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, BundleIDGenerator.DOWNSTREAM);

        try {
            clientWindow.moveWindowAhead(ack);
        } catch (RecievedOldACK | RecievedInvalidACK e) {
            System.out.println("Received Old/Invalid ACK!");
            e.printStackTrace();
        }
    }

    /* Check if window is full
     * 
     */
    public boolean isClientWindowFull(String clientID) throws ClientWindowNotFound
    {
        return getClientWindow(clientID).isWindowFull();
    }

    /* Return the bundles in the client's window
     * Parameter:
     * clientID     : encoded clientID
     * Returns:
     * Array of bundlesIDs present in the client's window
     */
    public String[] getclientWindow(String clientID) throws ClientWindowNotFound, InvalidClientIDException, BundleIDCryptographyException
    {
        String[] bundleIDs = getClientWindow(clientID).getWindow();
        for (int i=0; i < bundleIDs.length; ++i) {
            bundleIDs[i] = serverSecurity.encryptBundleID(bundleIDs[i], clientID);
        }

        return bundleIDs;
    }

    public int compareBundleIDs(String id1, String id2, String clientID, boolean direction) throws BundleIDCryptographyException
    {
        String decryptedBundleID1 = serverSecurity.decryptBundleID(id1, clientID);
        String decryptedBundleID2 = serverSecurity.decryptBundleID(id2, clientID);

        return BundleIDGenerator.compareBundleIDs(decryptedBundleID1, decryptedBundleID2, direction);
    }

    public long getCounterFromBundleID(String bundleID, String clientID, boolean direction) throws BundleIDCryptographyException
    {
        String decryptedBundleID = serverSecurity.decryptBundleID(bundleID, clientID);
        return BundleIDGenerator.getCounterFromBundleID(decryptedBundleID, direction);
    }
}