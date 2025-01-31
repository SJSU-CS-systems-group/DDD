package net.discdd.app.k9.repository;

import net.discdd.app.k9.repository.entity.K9ClientIdToEmailMapping;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface K9ClientIdToEmailMappingRepository  extends CrudRepository<K9ClientIdToEmailMapping, String>  {}