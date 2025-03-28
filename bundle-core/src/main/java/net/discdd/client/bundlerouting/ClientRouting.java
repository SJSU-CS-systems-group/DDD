package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParseException;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.pathutils.ClientPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

public class ClientRouting {

    private static final Logger logger = Logger.getLogger(ClientRouting.class.getName());

    private static ClientRouting singleClientRoutingInstance = null;
    HashMap<String, Long> metadata = null;
    ClientPaths clientPaths;

    /* Initialize client routing score table
     * Reads from json file if it exists, creates a new table otherwise
     * Parameters:
     * metaDataPath     : Path to file to store/read the meta
     * Return:
     * None
     */
    private ClientRouting(ClientPaths clientPaths) throws IOException, ClientMetaDataFileException {
        this.clientPaths = clientPaths;
        ObjectMapper objectMapper = new ObjectMapper();

        metadata = new HashMap<>();

        if (clientPaths.metadataFile.exists()) {
            try {
                metadata =
                        objectMapper.readValue(clientPaths.metadataFile, new TypeReference<HashMap<String, Long>>() {});
            } catch (JsonParseException | JsonMappingException e) {
                throw new ClientMetaDataFileException("Corrupted JSON File:" + e);
            }
        } else {
            objectMapper.writeValue(clientPaths.metadataFile, metadata);
        }
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
    public void updateMetaData(String senderId) throws ClientMetaDataFileException {
        if (senderId == null) {
            throw new IllegalArgumentException("senderId cannot be null");
        }

        long count = 1;

        if (metadata.containsKey(senderId)) {
            count += metadata.get(senderId);
            metadata.put(senderId, count);
        } else {
            metadata.put(senderId, count);
        }

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readTree(clientPaths.metadataFile);
            ((ObjectNode) rootNode).put(senderId, count);
            mapper.writeValue(clientPaths.metadataFile, rootNode);
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
    public byte[] bundleMetaData() throws IOException {
        return Files.readAllBytes(clientPaths.metadataFile.toPath());
    }
}