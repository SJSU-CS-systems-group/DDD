package net.discdd.server.bundlerouting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.entity.ServerRouting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

@Service
public class BundleRouting {
    private static final Logger logger = Logger.getLogger(BundleRouting.class.getName());
    private final String METADATAFILE = "routing.metadata";
    // size of the transportID column
    private static final int MAX_TRANSPORT_ID_LENGTH = 256;

    @Autowired
    ServerRoutingRepository serverRoutingRepository;

    // must match the senderId in recency blobs, which clients copy into routing.metadata
    public static String transportIdFor(PublicKey transportKey) {
        return SecurityUtils.generateID(transportKey.getEncoded());
    }

    public List<String> getClientsForTransportId(String transportId) {
        List<ServerRouting> serverRoutings = serverRoutingRepository.findByServerRoutingIdTransportID(transportId);
        List<String> clientIDs = new ArrayList<>();
        if (serverRoutings != null) {
            for (ServerRouting serverRouting : serverRoutings) {
                clientIDs.add(serverRouting.getServerRoutingId().getClientID());
            }
        }
        return clientIDs;
    }

    // adds the transport -> client mapping if it isn't there yet
    private void ensureEntry(String transportID, String clientID) {
        if (null == transportID) return;
        if (serverRoutingRepository.findByServerRoutingIdClientIDAndServerRoutingIdTransportID(clientID,
                                                                                               transportID) == null) {
            logger.log(FINE, String.format("Adding %s : %s mapping in serverRoutingRepository", transportID, clientID));
            serverRoutingRepository.save(new ServerRouting(transportID, clientID, "1"));
        }
    }

    /*
     * The transports listed in routing.metadata. Only the transport IDs are used, so the list can be a JSON
     * object (values such as counts are ignored and may be missing) or a JSON array of IDs.
     * Returns null if the file can't be read.
     */
    private Set<String> readTransportList(Path metadataPath, String clientID) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(metadataPath.toFile());
        } catch (IOException e) {
            logger.log(WARNING, "Error reading client metadata for " + clientID, e);
            return null;
        }
        if (root == null || !(root.isObject() || root.isArray())) {
            logger.log(WARNING, "Unexpected client metadata format for " + clientID);
            return null;
        }
        Set<String> transportIds = new HashSet<>();
        if (root.isObject()) {
            root.fieldNames().forEachRemaining(transportIds::add);
        } else {
            root.forEach(element -> transportIds.add(element.asText()));
        }
        transportIds.removeIf(transportId -> {
            boolean invalid = transportId.isBlank() || transportId.length() > MAX_TRANSPORT_ID_LENGTH;
            if (invalid) logger.log(WARNING, "Ignoring invalid transport id in metadata for " + clientID);
            return invalid;
        });
        return transportIds;
    }

    /*
     * payloadPath: path of received payload where routing metadata file exists
     * transportID: the transport that delivered the bundle, or null if the client uploaded it directly
     */
    public void processClientMetaData(Path payloadPath, String transportID, String clientID) throws
            RoutingExceptions.ClientMetaDataFileException, SQLException {
        logger.log(FINE, "processing client metadata, for transportId: " + transportID, ",clientID: " + clientID);
        Set<String> listedTransports = readTransportList(payloadPath.resolve(METADATAFILE), clientID);

        if (listedTransports != null) {
            for (String listedTransport : listedTransports) {
                ensureEntry(listedTransport, clientID);
            }
        }

        // a non-empty list is the client's whole list; an empty one (older clients) removes nothing
        if (listedTransports != null && !listedTransports.isEmpty()) {
            for (ServerRouting serverRouting : serverRoutingRepository.findByServerRoutingIdClientID(clientID)) {
                String routedTransport = serverRouting.getServerRoutingId().getTransportID();
                if (!listedTransports.contains(routedTransport) && !routedTransport.equals(transportID)) {
                    serverRoutingRepository.delete(serverRouting);
                }
            }
        }

        ensureEntry(transportID, clientID);
    }

    public void addClient(String clientId, int windowLength) {}

    public void updateClientWindow(String clientID, String bundleID) {}
}
