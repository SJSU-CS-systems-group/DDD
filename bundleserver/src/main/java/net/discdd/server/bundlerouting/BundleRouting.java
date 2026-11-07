package net.discdd.server.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.entity.ServerRouting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
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

    @Value("${bundle-server.routing.max-transports-per-client:10}")
    int maxTransportsPerClient = 10;

    // must match the senderId in recency blobs, which clients copy into routing.metadata
    public static String transportIdFor(PublicKey transportKey) {
        return SecurityUtils.generateID(transportKey.getEncoded());
    }

    // clients for which this transport is among their top maxTransportsPerClient (ties included)
    public List<String> getClientsForTransportId(String transportId) {
        List<ServerRouting> serverRoutings = serverRoutingRepository.findByServerRoutingIdTransportID(transportId);
        List<String> clientIDs = new ArrayList<>();
        if (serverRoutings != null) {
            for (ServerRouting serverRouting : serverRoutings) {
                String clientId = serverRouting.getServerRoutingId().getClientID();
                if (scoreOf(serverRouting) >= cutoffScore(clientId)) {
                    clientIDs.add(clientId);
                }
            }
        }
        return clientIDs;
    }

    private long cutoffScore(String clientId) {
        List<Long> scores = new ArrayList<>();
        for (ServerRouting serverRouting : serverRoutingRepository.findByServerRoutingIdClientID(clientId)) {
            scores.add(scoreOf(serverRouting));
        }
        if (maxTransportsPerClient <= 0 || scores.size() <= maxTransportsPerClient) return Long.MIN_VALUE;
        scores.sort(Comparator.reverseOrder());
        return scores.get(maxTransportsPerClient - 1);
    }

    private static long scoreOf(ServerRouting serverRouting) {
        try {
            return Long.parseLong(serverRouting.getScore());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateEntry(String transportID, String clientID, long score) {
        logger.log(FINE, String.format("Updating %s : %s mapping in serverRoutingRepository", transportID, clientID));
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

    // adds the transport without lowering an existing score
    private void ensureEntry(String transportID, String clientID) {
        if (null == transportID) return;
        ServerRouting serverRouting =
                serverRoutingRepository.findByServerRoutingIdClientIDAndServerRoutingIdTransportID(clientID,
                                                                                                   transportID);
        if (null == serverRouting || scoreOf(serverRouting) < 1) {
            updateEntry(transportID, clientID, 1);
        }
    }

    /*
     * payloadPath: path of received payload where routing metadata file exists
     * transportID: the transport that delivered the bundle, or null if the client uploaded it directly
     */
    public void processClientMetaData(Path payloadPath, String transportID, String clientID) throws
            RoutingExceptions.ClientMetaDataFileException, SQLException {
        logger.log(FINE, "processing client metadata, for transportId: " + transportID, ",clientID: " + clientID);
        var clientMetaDataPath = payloadPath.resolve(METADATAFILE);
        HashMap<String, Long> clientMap = null;
        ObjectMapper mapper = new ObjectMapper();

        try {
            clientMap = mapper.readValue(clientMetaDataPath.toFile(), new TypeReference<HashMap<String, Long>>() {});
        } catch (Exception e) {
            logger.log(WARNING, "Error reading client metadata for " + clientID, e);
        }

        // the client's counts are a full snapshot, so they replace the stored scores
        if (clientMap != null) {
            for (Map.Entry<String, Long> entry : clientMap.entrySet()) {
                updateEntry(entry.getKey(), clientID, entry.getValue());
            }
        }

        // a non-empty list is the client's whole list; an empty one (older clients) removes nothing
        if (clientMap != null && !clientMap.isEmpty()) {
            for (ServerRouting serverRouting : serverRoutingRepository.findByServerRoutingIdClientID(clientID)) {
                String routedTransport = serverRouting.getServerRoutingId().getTransportID();
                if (!clientMap.containsKey(routedTransport) && !routedTransport.equals(transportID)) {
                    serverRoutingRepository.delete(serverRouting);
                }
            }
        }

        if (clientMap == null || !clientMap.containsKey(transportID)) {
            ensureEntry(transportID, clientID);
        }
    }

    public void addClient(String clientId, int windowLength) {}

    public void updateClientWindow(String clientID, String bundleID) {}
}
