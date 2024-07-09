package net.discdd.server.repository;

import java.util.List;

import net.discdd.server.repository.entity.SentAduDetails;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SentAduDetailsRepository extends CrudRepository<SentAduDetails, String> {
    // public Optional<SentAduDetails> findById(String id);

    List<SentAduDetails> findByBundleId(String bundleId);

    // public void save(SentAduDetails record);

    void deleteByBundleId(String bundleId);
}
