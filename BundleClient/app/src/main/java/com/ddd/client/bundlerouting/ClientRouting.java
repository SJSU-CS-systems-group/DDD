package com.ddd.client.bundlerouting;

import static java.util.logging.Level.INFO;

import com.ddd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.logging.Logger;

public class ClientRouting {

    private static final Logger logger = Logger.getLogger(ClientRouting.class.getName());

    private static ClientRouting singleClientRoutingInstance = null;
    HashMap<String, Long> metadata = null;
    Path metaDataPath = null;
    private final String METADATAFILE = "routing.metadata";

    /* Initialize client routing score table
     * Reads from json file if it exists, creates a new table otherwise
     * Parameters:
     * metaDataPath     : Path to file to store/read the meta
     * Return:
     * None
     */
    private ClientRouting(Path rootPath) throws IOException, ClientMetaDataFileException {
        this.metaDataPath = rootPath.resolve("BundleRouting");
        metaDataPath.toFile().mkdirs();

        metaDataPath = metaDataPath.resolve(METADATAFILE);

        File metadataFile = metaDataPath.toFile();
        ObjectMapper objectMapper = new ObjectMapper();

        metadata = new HashMap<>();

        if (metadataFile.exists()) {
            try {
                metadata = objectMapper.readValue(metadataFile, new TypeReference<HashMap<String, Long>>() {});
            } catch (JsonParseException | JsonMappingException e) {
                throw new ClientMetaDataFileException("Corrupted JSON File:" + e);
            }
        } else {
            objectMapper.writeValue(metadataFile, metadata);
        }
    }

    public static ClientRouting initializeInstance(Path metaDataPath) throws ClientMetaDataFileException, IOException {
        if (singleClientRoutingInstance == null) {
            singleClientRoutingInstance = new ClientRouting(metaDataPath);
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
    public void updateMetaData(String transportID) throws ClientMetaDataFileException {
        long count = 1;

        if (metadata.containsKey(transportID)) {
            count += metadata.get(transportID);
            metadata.put(transportID, count);
        } else {
            metadata.put(transportID, count);
        }

        ObjectMapper mapper = new ObjectMapper();
        try {
            File metadataFile = metaDataPath.toFile();
            JsonNode rootNode = mapper.readTree(metadataFile);
            ((ObjectNode) rootNode).put(transportID, count);
            mapper.writeValue(metadataFile, rootNode);
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
    public void bundleMetaData(Path bundlePath) throws ClientMetaDataFileException, IOException {
        Files.copy(metaDataPath, bundlePath.resolve(METADATAFILE));
    }
}