package com.ddd.server.bundlerouting;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ddd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import com.ddd.server.storage.SNRDatabases;
import static java.util.logging.Level.*;

@Repository
public class ServerRouting {

    private static final Logger logger = Logger.getLogger(ServerRouting.class.getName());
    SNRDatabases database = null;
    private static final String dbTableName = "ServerRoutingTable";
    private final String METADATAFILE = "routing.metadata";

    @Autowired
    private Environment env;

    // public ServerRouting() throws SQLException
    // {
    //     // TODO: Change to config
    //     String url = "jdbc:mysql://localhost:3306";
    //     String uname = "root";
    //     String password = "mchougule478";
    //     String dbName = "DTN_SERVER_DB";

    //     database = new SNRDatabases(url, uname, password, dbName);

    //     String dbTableCreateQuery = "CREATE TABLE " + dbTableName + " " +
    //             "(transportID VARCHAR(256) not NULL," +
    //             "clientID VARCHAR(256) not NULL," +
    //             "score VARCHAR(256)," +
    //             "PRIMARY KEY (transportID, clientID))";

    //     database.createTable(dbTableCreateQuery);
    // }

    @PostConstruct
    public void init() throws SQLException {
        String url = env.getProperty("spring.datasource.url");
        String uname = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String dbName = env.getProperty("spring.datasource.db-name");

        database = new SNRDatabases(url, uname, password, dbName);

        logger.log(INFO, "Create ServerRoutingTable");

        String dbTableCreateQuery = "CREATE TABLE " + dbTableName + " " + "(transportID VARCHAR(256) not NULL," +
                "clientID VARCHAR(256) not NULL," + "score VARCHAR(256)," + "PRIMARY KEY (transportID, clientID))";

        database.createTable(dbTableCreateQuery);
    }

    /* Updates the client entry for the transport ID
     * Parameters:
     * transportID  : encoded transport ID
     * clientID     : encoded client ID
     * score        : score received for the transport from the client
     */
    private void updateEntry(String transportID, String clientID, long score) throws SQLException {
        String query = "INSERT INTO " + dbTableName + " VALUES " + "('" + transportID + "', '" + clientID + "', '" +
                Long.toUnsignedString(score) + "')" + " ON DUPLICATE KEY UPDATE score = '" +
                Long.toUnsignedString(score) + "'";

        database.updateEntry(query);
    }

    /* Returns the sorted list of clients accessible via the transport
     * Parameters:
     * transportID  : encoded transport ID
     * Returns:
     * List of client IDs sorted based on score
     */
    public List<String> getClients(String transportID) throws SQLException {
        String query = "SELECT clientID from " + dbTableName + " WHERE transportID = '" + transportID +
                "' ORDER BY score DESC";

        List<String[]> results = database.getFromTable(query);

        List<String> clients = new ArrayList<>();

        for (String[] result : results) {
            clients.add(result[0]);
        }

        return clients;
    }

    /* Parses the client metadata and updates the respective scores
     * Parameters:
     * clientMetaDataPath   : Path to the json metadata file
     * clientID             : encoded clientID
     * Returns:
     * None
     */
    public void processClientMetaData(String payloadPath, String transportID, String clientID) throws ClientMetaDataFileException, SQLException {

        String clientMetaDataPath = payloadPath + File.separator + METADATAFILE;
        HashMap<String, Long> clientMap = null;
        ObjectMapper mapper = new ObjectMapper();

        try {
            clientMap = mapper.readValue(new File(clientMetaDataPath), new TypeReference<HashMap<String, Long>>() {});
        } catch (Exception e) {
            throw new ClientMetaDataFileException("Error Reading client metadata: " + e);
        }

        if (clientMap == null || clientMap.keySet().isEmpty()) {
            logger.log(WARNING, "[BundleRouting]: Client Metadata is empty, initializing transport with score 0");
            updateEntry(transportID, clientID, 0);
        } else {
            for (Map.Entry<String, Long> entry : clientMap.entrySet()) {
                updateEntry(entry.getKey(), clientID, entry.getValue());
            }
        }
    }

    public List<String> getTransports(Optional<String> clientID) throws SQLException {
        String query = "SELECT DISTINCT transportID from " + dbTableName;

        if (clientID != null) {
            query += " WHERE clientID = '" + clientID + "'";
        }

        List<String[]> results = database.getFromTable(query);

        List<String> transports = new ArrayList<>();

        for (String[] result : results) {
            transports.add(result[0]);
        }

        return transports;
    }

}