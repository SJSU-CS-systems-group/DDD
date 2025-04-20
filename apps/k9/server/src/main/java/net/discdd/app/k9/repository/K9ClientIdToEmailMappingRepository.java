package net.discdd.app.k9.repository;

import net.discdd.app.k9.repository.entity.K9ClientIdToEmailMapping;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface K9ClientIdToEmailMappingRepository extends CrudRepository<K9ClientIdToEmailMapping, String> {
    @Transactional
    @Modifying
    @Query(value = "UPDATE k9client_id_to_email_mapping SET client_id = :newClientId WHERE email = :email",
            nativeQuery = true)
    void updateClientId(@Param("newClientId") String newClientId, @Param("email") String email);
}
