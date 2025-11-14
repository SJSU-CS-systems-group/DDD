package net.discdd.server.service;

import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TransportMessageService {

    private final TransportMessageRepository repo;

    public TransportMessageService(TransportMessageRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TransportMessage createMessage(int transportId, String messageText) {
        Long max = repo.findMaxMessageNumber(transportId);
        long next = (max == null ? 1L : max + 1);

        TransportMessage msg = new TransportMessage(
                new MessageKey(transportId, next),
                messageText,
                LocalDateTime.now(),
                null
        );

        return repo.save(msg);
    }
}

