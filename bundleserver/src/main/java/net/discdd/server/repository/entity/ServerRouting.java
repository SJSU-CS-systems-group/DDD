package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.discdd.server.repository.compositeId.ServerRoutingId;

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

    /**
     * this constructor is used by JPA
     */
    private ServerRouting() {}

    public ServerRouting(String transportId, String clientId, String score) {
        this.serverRoutingId = new ServerRoutingId();
        this.serverRoutingId.setTransportID(transportId);
        this.serverRoutingId.setClientID(clientId);
        this.score = score;
    }

    @Override
    public String toString() {
        return "ServerRouting{" + "transportID='" + serverRoutingId.getTransportID() + '\'' + ", clientID='" +
                serverRoutingId.getClientID() + '\'' + ", score ='" + score + '\'' + '}';
    }
}
