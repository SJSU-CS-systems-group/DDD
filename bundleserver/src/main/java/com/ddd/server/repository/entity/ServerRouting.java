package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Entity(name = "ServerRouting")
@Table(name = "serverroutingtable")
@Getter
@Setter
@AllArgsConstructor
public class ServerRouting {

    @Id
    @Column(name = "transportID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String transportID;

    @Id
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;

    @Column(name = "score", columnDefinition = "VARCHAR(256)")
    private String score;

    @Override
    public String toString() {
        return "ServerRouting{" + "transportID='" + transportID + '\'' + ", clientID='" + clientID + '\'' +
                ", score ='" + score + '\'' + '}';
    }
}
