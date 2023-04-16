package com.ddd.server.bundlerouting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import com.ddd.server.bundlesecurity.BundleID;
import com.ddd.server.bundlesecurity.ServerSecurity;
import com.ddd.server.bundlesecurity.SecurityExceptions.ClientSessionException;
import com.ddd.server.bundlerouting.WindowUtils.Window;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.BufferOverflow;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.ClientAlreadyExists;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.ClientNotFound;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidBundleID;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;

public class ServerWindow {
    private HashMap<String, Window> clientHashMap;

    public ServerWindow()
    {
        clientHashMap   = new HashMap<>();
    }

    /* Returns the window for the requested client
     * Parameters:
     * clientID     : encoded clientID
     * Returns:
     * Window object
     */
    private Window getClientWindow(String clientID) throws ClientNotFound
    {
        if (!clientHashMap.containsKey(clientID)) {
            throw new ClientNotFound("ClientID["+clientID+"] Not Found");
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
    public void updateClientWindow(String clientID, String bundleID) throws ClientNotFound, BufferOverflow, InvalidBundleID
    {
        getClientWindow(clientID).add(bundleID);
    }

    public String getCurrentbundleID(String clientID) throws ClientNotFound
    {
        return getClientWindow(clientID).getCurrentbundleID(clientID);
    }

    /* Return the latest bundleID in the client's window
     * Parameter:
     * clientID     : encoded clientID
     * Returns:
     * Latest bundle ID in window
     */
    public String getLatestClientBundle(String clientID) throws ClientNotFound
    {
        return getClientWindow(clientID).getLatestBundleID();
    }

    /* Move window ahead based on the ACK received
     * Parameters:
     * clientID   : encoded clientID
     * ackPath    : Path to the encoded acknowledgement (unencrypted)
     * Returns:
     * None
     */
    public void processACK(String clientID, String ackPath) throws ClientNotFound, InvalidLength, IOException
    {
        Window clientWindow = getClientWindow(clientID);
        String ackedBundleID = new String(Files.readAllBytes(Paths.get(ackPath)));
        System.out.println("Ack from file = "+ackedBundleID);
        long ack = BundleID.getCounterFromBundleID(ackedBundleID, BundleID.DOWNSTREAM);

        clientWindow.moveWindowAhead(ack);
    }

    /* Check if window is full
     * 
     */
    public boolean isClientWindowFull(String clientID) throws ClientNotFound
    {
        return getClientWindow(clientID).isWindowFull();
    }

    /* Return the bundles in the client's window
     * Parameter:
     * clientID     : encoded clientID
     * Returns:
     * Array of bundlesIDs present in the client's window
     */
    public String[] getclientWindow(String clientID, ServerSecurity server) throws ClientNotFound, InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, org.whispersystems.libsignal.InvalidKeyException, ClientSessionException, IOException
    {
        String[] bundleIDs = getClientWindow(clientID).getWindow();
        for (int i=0; i < bundleIDs.length; ++i) {
            bundleIDs[i] = server.encryptBundleID(bundleIDs[i], clientID);
        }

        return bundleIDs;
    }
}