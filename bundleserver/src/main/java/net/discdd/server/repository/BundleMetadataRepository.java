package net.discdd.server.repository;

import net.discdd.server.repository.entity.BundleMetadata;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BundleMetadataRepository extends CrudRepository<BundleMetadata, String> {}
