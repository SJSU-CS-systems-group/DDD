package net.discdd.bundle;

import net.discdd.server.repository.ClientBundleCountersRepository;
import net.discdd.server.repository.entity.ClientBundleCounters;

class InMemoryClientBundleCountersRepository extends InMemoryCrudRepository<ClientBundleCounters, String>
        implements ClientBundleCountersRepository {
    public InMemoryClientBundleCountersRepository() {
        super(e -> e.clientId);
    }
}
