package net.discdd.server.repository;

import net.discdd.server.repository.entity.ReceivedBundle;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Upstream bundles the server has received; see {@link ReceivedBundle}. */
@Repository
public interface ReceivedBundleRepository extends CrudRepository<ReceivedBundle, String> {
    /** the highest upstream counter received from {@code clientId}, or null if none */
    @Query("select max(r.bundleCounter) from ReceivedBundle r where r.clientId = ?1")
    Long findMaxBundleCounterByClientId(String clientId);

    /** any bundle carrying this client key fingerprint; all of them name the same client */
    Optional<ReceivedBundle> findFirstByClientKeyFingerprint(String clientKeyFingerprint);
}
