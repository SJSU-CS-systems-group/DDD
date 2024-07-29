package net.discdd.server.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.entity.ServerRouting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

@Service
public class BundleRouting {
    private static final Logger logger = Logger.getLogger(BundleRouting.class.getName());
    private final String METADATAFILE = "routing.metadata";

    @Autowired
    ServerRoutingRepository serverRoutingRepository;

    public List<String> getClients(String transportId) {
        List<ServerRouting> serverRoutings = serverRoutingRepository.findByServerRoutingIdTransportID(transportId);
        List<String> clientIDs = new ArrayList<>();
        if (serverRoutings != null) {
            for (ServerRouting serverRouting : serverRoutings) {
                clientIDs.add(serverRouting.getServerRoutingId().getClientID());
            }
        }
        return clientIDs;
    }

    private void updateEntry(String transportID, String clientID, long score) {
        logger.log(FINE, "Updating transport-client mapping entry in serverRoutingRepository");
        if (null == transportID) return;
        ServerRouting serverRouting =
                serverRoutingRepository.findByServerRoutingIdClientIDAndServerRoutingIdTransportID(clientID,
                                                                                                   transportID);
        if (null == serverRouting) {
            serverRouting = new ServerRouting(transportID, clientID, String.valueOf(score));
        } else {
            serverRouting.setScore(String.valueOf(score));
        }
        serverRoutingRepository.save(serverRouting);
        logger.log(FINE, "Updating transport-client mapping entry in serverRoutingRepository");
    }

    /*
     * payloadPath: path of received payload where routing metadata file exists
     */
    public void processClientMetaData(String payloadPath, String transportID, String clientID) throws RoutingExceptions.ClientMetaDataFileException, SQLException {
        logger.log(FINE, "processing client metadata, for transportId: "+transportID, ",clientID: "+clientID);
        String clientMetaDataPath = payloadPath + File.separator + METADATAFILE;
        HashMap<String, Long> clientMap = null;
        ObjectMapper mapper = new ObjectMapper();

        try {
            clientMap = mapper.readValue(new File(clientMetaDataPath), new TypeReference<HashMap<String, Long>>() {});
        } catch (Exception e) {
            throw new RoutingExceptions.ClientMetaDataFileException("Error Reading client metadata: " + e);
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

    public void addClient(String clientId, int windowLength) {}

    public void updateClientWindow(String clientID, String bundleID) {}
}
