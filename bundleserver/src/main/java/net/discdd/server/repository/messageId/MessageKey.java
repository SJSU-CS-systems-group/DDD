package net.discdd.server.repository.messageId;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageKey implements Serializable {
    private String transportId;
    private long messageNumber;
}

