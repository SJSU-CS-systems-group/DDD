package net.discdd.server.repository.messageId;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public class MessageKey implements Serializable {

    private int transportId;
    private int messageNumber;
    public MessageKey() {}

    public MessageKey(int transportId, int messageNumber) {
        this.transportId = transportId;
        this.messageNumber = messageNumber;
    }

}

