package net.discdd.server.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
public class TransportMessage {
    @Id
    public String transportId;

    /**
     * The message being sent to the transport
     */
    public String message;

    public TransportMessage(String transportId, String message) {
        this.transportId = transportId;
        this.message = message;
    }


}
