package net.discdd.server.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.discdd.server.repository.entity.BundleMetadata;

@Repository
public interface BundleMetadataRepository extends CrudRepository<BundleMetadata, String> {}
