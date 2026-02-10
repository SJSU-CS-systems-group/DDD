package net.discdd.server.service;

import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.repository.entity.TransportMessage;
import net.discdd.server.repository.messageId.MessageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;

@Service
public class TransportMessageService {

    private final TransportMessageRepository repo;

    public TransportMessageService(TransportMessageRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TransportMessage createMessage(@Nonnull String transportId, @Nonnull String messageText) {
        int maxRetries = 5;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Long max = repo.findMaxMessageNumber(transportId);
            long next = (max == null ? 1L : max + 1);
            TransportMessage msg =
                    new TransportMessage(new MessageKey(transportId, next), messageText, LocalDateTime.now(), null);
            try {
                return repo.save(msg);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Another transaction inserted the same messageNumber, retry
                if (attempt == maxRetries - 1) {
                    throw new RuntimeException(
                            "Failed to create message after " + maxRetries + " attempts due to concurrent inserts.", e);
                }
                // else, loop and retry
            }
        }
        throw new RuntimeException("Unexpected error in createMessage retry logic.");
    }
}

