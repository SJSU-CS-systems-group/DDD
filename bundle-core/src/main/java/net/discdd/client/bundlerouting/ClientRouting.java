package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParseException;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.pathutils.ClientPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class ClientRouting {

    private static final Logger logger = Logger.getLogger(ClientRouting.class.getName());

    public static final long TRANSPORT_EXPIRATION_MS = 30L * 24 * 60 * 60 * 1000;

    private static ClientRouting singleClientRoutingInstance = null;
    // sent to the server as routing.metadata
    HashMap<String, Long> metadata = null;
    // client only, used to expire transports
    HashMap<String, Long> lastSeen = null;
    ClientPaths clientPaths;
    private final LongSupplier clock;

    /* Initialize client routing score table
     * Reads from json file if it exists, creates a new table otherwise
     * Parameters:
     * metaDataPath     : Path to file to store/read the meta
     * Return:
     * None
     */
    private ClientRouting(ClientPaths clientPaths) throws IOException, ClientMetaDataFileException {
        this(clientPaths, System::currentTimeMillis);
    }

    ClientRouting(ClientPaths clientPaths, LongSupplier clock) throws IOException, ClientMetaDataFileException {
        this.clientPaths = clientPaths;
        this.clock = clock;
        metadata = readMap(clientPaths.metadataFile);
        lastSeen = readMap(clientPaths.transportLastSeenFile);

        // older entries have no visit time yet
        long now = clock.getAsLong();
        for (String transportId : metadata.keySet()) {
            lastSeen.putIfAbsent(transportId, now);
        }
        lastSeen.keySet().retainAll(metadata.keySet());
        save();
    }

    private static HashMap<String, Long> readMap(File file) throws IOException, ClientMetaDataFileException {
        if (!file.exists()) {
            return new HashMap<>();
        }
        try {
            return new ObjectMapper().readValue(file, new TypeReference<HashMap<String, Long>>() {});
        } catch (JsonParseException | JsonMappingException e) {
            throw new ClientMetaDataFileException("Corrupted JSON File:" + e);
        }
    }

    private void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(clientPaths.metadataFile, metadata);
        mapper.writeValue(clientPaths.transportLastSeenFile, lastSeen);
    }

    private boolean expireOldTransports() {
        long cutoff = clock.getAsLong() - TRANSPORT_EXPIRATION_MS;
        boolean removed = lastSeen.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (removed) {
            metadata.keySet().retainAll(lastSeen.keySet());
        }
        return removed;
    }

    public static ClientRouting initializeInstance(ClientPaths clientPaths) throws ClientMetaDataFileException,
            IOException {
        if (singleClientRoutingInstance == null) {
            singleClientRoutingInstance = new ClientRouting(clientPaths);
        } else {
            logger.log(INFO, "[BR]: Client Routing Instance already Exists!");
        }
        return singleClientRoutingInstance;
    }

    public static ClientRouting getInstance() {
        if (singleClientRoutingInstance == null) {
            throw new IllegalStateException("Client Routing instance has not been initialized!");
        }
        return singleClientRoutingInstance;
    }

    /* Updates the score for the transport
     * Parameters:
     * transportID      : encoded transport ID
     * Returns:
     * None
     */
    public synchronized void updateMetaData(String senderId) throws ClientMetaDataFileException {
        if (senderId == null) {
            logger.log(WARNING, ("senderId cannot be null"));
            return;
        }
        metadata.merge(senderId, 1L, Long::sum);
        lastSeen.put(senderId, clock.getAsLong());
        expireOldTransports();

        try {
            save();
        } catch (Exception e) {
            throw new ClientMetaDataFileException("Error updating Routing Meta Data:" + e);
        }
    }

    /* Creates the metadata file in the bundle directory
     * Parameters:
     * bundlePath   : Path to the bundle to be sent
     * Returns:
     * None
     */
    public synchronized byte[] bundleMetaData() throws IOException {
        if (expireOldTransports()) {
            save();
        }
        return Files.readAllBytes(clientPaths.metadataFile.toPath());
    }
}
