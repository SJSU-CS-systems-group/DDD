package net.discdd.server.repository;

import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransportMessageRepository extends CrudRepository<TransportMessage, MessageKey> {

    @Query("SELECT MAX(t.messageKey.messageNumber) FROM TransportMessage t WHERE t.messageKey.transportId = " +
            ":transportId")
    Long findMaxMessageNumber(@Param("transportId") String transportId);

    @Query("SELECT t FROM TransportMessage t WHERE t.messageKey.transportId = :transportId AND t.messageKey" +
            ".messageNumber > :lastMessageId ORDER BY t.messageKey.messageNumber ASC")
    List<TransportMessage> findMessagesAfter(@Param("transportId") String transportId,
                                             @Param("lastMessageId") long lastMessageId);

}
