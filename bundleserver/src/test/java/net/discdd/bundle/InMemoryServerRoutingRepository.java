package net.discdd.bundle;

import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.compositeId.ServerRoutingId;
import net.discdd.server.repository.entity.ServerRouting;

import java.util.List;
import java.util.Optional;

class InMemoryServerRoutingRepository extends InMemoryCrudRepository<ServerRouting, ServerRoutingId>
        implements ServerRoutingRepository {
    public InMemoryServerRoutingRepository() {
        super(ServerRouting::getServerRoutingId);
    }

    @Override
    public List<ServerRouting> findByServerRoutingIdTransportID(String transportID) {
        return store.values()
                .stream()
                .filter(routing -> routing.getServerRoutingId().getTransportID().equals(transportID))
                .toList();
    }

    @Override
    public Optional<ServerRouting> findByServerRoutingIdClientID(String clientID) {
        return store.values()
                .stream()
                .filter(routing -> routing.getServerRoutingId().getClientID().equals(clientID))
                .findFirst();
    }

    @Override
    public ServerRouting findByServerRoutingIdClientIDAndServerRoutingIdTransportID(String clientID,
                                                                                    String transportID) {
        return store.values()
                .stream()
                .filter(routing -> routing.getServerRoutingId().getClientID().equals(clientID) &&
                        routing.getServerRoutingId().getTransportID().equals(transportID))
                .findFirst()
                .orElse(null);
    }
}
