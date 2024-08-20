package net.discdd.server.repository.compositeId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@EqualsAndHashCode
public class ServerRoutingId {
    @Column(name = "transportID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String transportID;
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;
}
