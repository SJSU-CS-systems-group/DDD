package net.discdd.bundle;

import net.discdd.server.repository.ServerWindowRepository;
import net.discdd.server.repository.entity.ServerWindow;

class InMemoryServerWindowRepository extends InMemoryCrudRepository<ServerWindow, String>
        implements ServerWindowRepository {
    public InMemoryServerWindowRepository() {
        super(ServerWindow::getClientID);
    }

    @Override
    public ServerWindow findByClientID(String clientID) {
        return null;
    }
}
