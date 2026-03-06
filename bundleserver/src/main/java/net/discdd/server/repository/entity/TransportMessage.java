package net.discdd.server.repository.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import net.discdd.server.repository.messageId.MessageKey;

import java.time.Instant;

@Entity
@AllArgsConstructor
@NoArgsConstructor
public class TransportMessage {
    @EmbeddedId
    public MessageKey messageKey;

    public String message;

    // Date when the message was created (UTC)
    public Instant sentAt;

    // Date when the message was read, null if unread.
    public Instant readDate;

}
