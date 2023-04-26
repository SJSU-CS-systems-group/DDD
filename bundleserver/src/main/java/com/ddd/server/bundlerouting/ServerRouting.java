package com.ddd.server.bundlerouting;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ddd.server.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import com.ddd.server.storage.SNRDatabases;

public class ServerRouting {

    SNRDatabases                database     = null;
    private static final String dbTableName  = "ServerRoutingTable";
    private final String        METADATAFILE = "routing.metadata";


    public ServerRouting() throws SQLException
    {
        // TODO: Change to config
        String url = "jdbc:mysql://localhost:3306";
        String uname    = "root";
        String password = "password";
        String dbName = "SNRDatabase";

        database = new SNRDatabases(url, uname, password, dbName);

        String dbTableCreateQuery = "CREATE TABLE "+ dbTableName+ " " +
                                    "(transportID VARCHAR(256) not NULL," +
                                    "clientID VARCHAR(256) not NULL," +
                                    "score VARCHAR(256)," +
                                    "PRIMARY KEY (transportID, clientID))";

        database.createTable(dbTableCreateQuery);
    }

    /* Updates the client entry for the transport ID
     * Parameters:
     * transportID  : encoded transport ID
     * clientID     : encoded client ID
     * score        : score received for the transport from the client
     */
    private void updateEntry(String transportID, String clientID, long score) throws SQLException
    {
        String query = "INSERT INTO " + dbTableName + " VALUES "+
                             "('"+transportID+"', '"+clientID+"', '"+Long.toUnsignedString(score)+"')"+
                             " ON DUPLICATE KEY UPDATE score = '"+Long.toUnsignedString(score)+"'";

        database.updateEntry(query);
    }

    /* Returns the sorted list of clients accessible via the transport
     * Parameters:
     * transportID  : encoded transport ID
     * Returns:
     * List of client IDs sorted based on score
     */
    public List<String> getClients(String transportID) throws SQLException
    {
        String query =  "SELECT clientID from "+dbTableName+
                        " WHERE transportID = '"+transportID+"' ORDER BY score DESC";

        List<String[]> results = database.getFromTable(query);

        List<String> clients = new ArrayList<>();

        for (String[] result: results) {
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
    public void processClientMetaData(String payloadPath, String clientID) throws ClientMetaDataFileException, SQLException
    {
        // TODO: Read metadata from payload instead of directly
        String clientMetaDataPath = payloadPath + File.separator + METADATAFILE;
        HashMap<String, Long> clientMap = null;
        ObjectMapper mapper = new ObjectMapper();
        
        try {
            clientMap = mapper.readValue(new File(clientMetaDataPath), new TypeReference<HashMap<String, Long>>() {});
        } catch (Exception e) {
            throw new ClientMetaDataFileException("Error Reading client metadata: "+e);
        }

        for (Map.Entry<String,Long> entry: clientMap.entrySet()) {
            updateEntry(entry.getKey(), clientID, entry.getValue());
        }
    }

    public List<String> getTransports(Optional<String> clientID) throws SQLException
    {
        String query = "SELECT DISTINCT transportID from " + dbTableName;

        if (clientID != null) {
            query += " WHERE clientID = '"+clientID+"'";
        }

        List<String[]> results = database.getFromTable(query);

        List<String> transports = new ArrayList<>();

        for (String[] result : results) {
            transports.add(result[0]);
        }

        return transports;
    }

}