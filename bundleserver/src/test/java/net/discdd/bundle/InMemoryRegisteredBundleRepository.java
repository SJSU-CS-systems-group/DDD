package net.discdd.bundle;

import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.entity.RegisteredAppAdapter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class InMemoryRegisteredBundleRepository extends InMemoryCrudRepository<RegisteredAppAdapter, String> implements RegisteredAppAdapterRepository {
    public InMemoryRegisteredBundleRepository() {
        super(RegisteredAppAdapter::getAppId);
    }

    @Override
    public Optional<RegisteredAppAdapter> findByAppId(String appId) {
        return Optional.empty();
    }

    @Override
    public Collection<String> findAllAppIds() {
        return store.keySet();
    }
}
