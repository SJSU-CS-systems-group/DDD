package net.discdd.bundle;

import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.entity.SentAduDetails;

import java.util.List;

class InMemorySentAduDetailsRepository extends InMemoryCrudRepository<SentAduDetails, String>
        implements SentAduDetailsRepository {
    public InMemorySentAduDetailsRepository() {
        super(e -> e.id.toString());
    }

    @Override
    public List<SentAduDetails> findByBundleId(String bundleId) {
        return store.values().stream().filter(details -> details.bundleId.equals(bundleId)).toList();
    }

    @Override
    public void deleteByBundleId(String bundleId) {
        for (var entry : findByBundleId(bundleId)) {
            store.remove(entry.id.toString());
        }
    }
}
