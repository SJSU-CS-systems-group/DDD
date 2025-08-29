package net.discdd.bundle;

import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.entity.BundleMetadata;

import java.util.function.Function;

public class InMemoryBundleMetadataRepository extends InMemoryCrudRepository<BundleMetadata, String> implements BundleMetadataRepository{
    public InMemoryBundleMetadataRepository() {
        super(bm -> bm.encryptedBundleId);
    }
}
