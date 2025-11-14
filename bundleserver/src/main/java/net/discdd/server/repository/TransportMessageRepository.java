package net.discdd.server.repository;

import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import org.springframework.data.repository.CrudRepository;

public interface TransportMessageRepository extends CrudRepository<TransportMessage, MessageKey> {

}
