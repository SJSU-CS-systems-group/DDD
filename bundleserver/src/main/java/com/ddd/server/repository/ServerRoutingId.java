package com.ddd.server.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class ServerRoutingId {
    @Column(name = "transportID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String transportID;
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;
}
