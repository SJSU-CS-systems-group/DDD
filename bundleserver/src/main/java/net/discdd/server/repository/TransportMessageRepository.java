package net.discdd.server.repository;

import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TransportMessageRepository extends CrudRepository<TransportMessage, MessageKey> {

    @Query("SELECT MAX(t.messageKey.messageNumber) FROM TransportMessage t WHERE t.messageKey.transportId = :transportId")
    Long findMaxMessageNumber(@Param("transportId") String transportId);

}
