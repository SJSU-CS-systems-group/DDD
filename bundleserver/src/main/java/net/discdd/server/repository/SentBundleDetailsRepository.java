package net.discdd.server.repository;

import java.util.Optional;

import net.discdd.server.repository.entity.SentBundleDetails;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SentBundleDetailsRepository extends CrudRepository<SentBundleDetails, String> {
    //  public Optional<SentBundleDetails> findByBundleId(String bundleId);
    //
    //  public void save(SentBundleDetails record);
    //
    //  public void deleteByBundleId(String bundleId);

    Optional<SentBundleDetails> findByBundleId(String bundleId);

    // public void save(SentBundleDetails sentBundleDetails);
}
