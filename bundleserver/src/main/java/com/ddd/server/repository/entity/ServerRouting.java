package com.ddd.server.repository.entity;

import com.ddd.server.repository.ServerRoutingId;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Entity(name = "ServerRouting")
@Table(name = "serverroutingtable")
@Getter
@Setter
@AllArgsConstructor
public class ServerRouting {

    @EmbeddedId
    private ServerRoutingId serverRoutingId;

    @Column(name = "score", columnDefinition = "VARCHAR(256)")
    private String score;

    @Override
    public String toString() {
        return "ServerRouting{" + "transportID='" + serverRoutingId.getTransportID() + '\'' + ", clientID='" + serverRoutingId.getClientID() + '\'' +
                ", score ='" + score + '\'' + '}';
    }
}
