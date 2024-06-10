package com.ddd.client.bundlerouting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.HashMap;

import com.ddd.client.bundlesecurity.SecurityUtils;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.ddd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;

public class ClientRouting {
    private static ClientRouting singleClientRoutingInstance = null;
    HashMap<String, Long> metadata = null;
    String metaDataPath = null;
    private final String METADATAFILE = "routing.metadata";

    /* Initialize client routing score table
     * Reads from json file if it exists, creates a new table otherwise
     * Parameters:
     * metaDataPath     : Path to file to store/read the meta
     * Return:
     * None
     */
    private ClientRouting(String rootPath) throws IOException, ClientMetaDataFileException {
        this.metaDataPath = rootPath + File.separator + "BundleRouting" + File.separator;
        SecurityUtils.createDirectory(metaDataPath);

        metaDataPath += METADATAFILE;

        File metadataFile = new File(metaDataPath);
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

    public static ClientRouting initializeInstance(String metaDataPath) throws ClientMetaDataFileException,
            IOException {
        if (singleClientRoutingInstance == null) {
            singleClientRoutingInstance = new ClientRouting(metaDataPath);
        } else {
            System.out.println("[BR]: Client Routing Instance already Exists!");
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
            File metadataFile = new File(metaDataPath);
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
    public void bundleMetaData(String bundlePath) throws ClientMetaDataFileException {
        String bundleMetaDataPath = bundlePath + File.separator + METADATAFILE;

        try {
            SecurityUtils.copyContent(new FileInputStream(metaDataPath), new FileOutputStream(bundleMetaDataPath));
        } catch (IOException e) {
            throw new ClientMetaDataFileException("Error copying Routing Meta Data to Bundle Path:" + e);
        }
    }
}