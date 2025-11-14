package net.discdd.server.repository.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import net.discdd.server.repository.messageId.MessageKey;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
public class TransportMessage {
    @EmbeddedId
    public MessageKey messageKey;

    public String message;
    public LocalDateTime messageDate;
    public LocalDateTime readDate;

}
