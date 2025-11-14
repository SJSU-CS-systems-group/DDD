package net.discdd.server.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import net.discdd.server.repository.messageId.MessageKey;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
public class TransportMessage {
    @Id
    public MessageKey messageId;

    public String message;
    public LocalDateTime messageDate;
    public LocalDateTime readDate;
    public TransportMessage() {}

    public TransportMessage(MessageKey id, LocalDateTime date, String message, LocalDateTime readDate) {
        this.messageId = id;
        this.messageDate = date;
        this.message = message;
        this.readDate = readDate;
    }

}
